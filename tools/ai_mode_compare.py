from __future__ import annotations

import json
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

BASE_URL = "http://localhost:33000"
EMAIL = "test@mamoji.com"
PASSWORD = "123456"

REPORT_PATH = Path("D:/projects/shuai/mamoji/.codex-smoke/ai-mode-compare.json")
SCREENSHOT_DIR = Path("D:/projects/shuai/mamoji/.codex-smoke/screenshots")

QUESTIONS = [
    "本月预算执行率怎么样，是否有超支风险？",
    "支出分类占比最高的是哪几类？给我前3名",
    "列出最近5笔支出流水，并标出金额最大的两笔",
    "本月收入、支出、结余分别是多少？并给两条节流建议",
]

MODES = ["Auto", "Agent", "LLM"]


@dataclass
class QAResult:
    mode: str
    question: str
    answer: str
    elapsed_ms: int
    answer_len: int
    has_conclusion: bool
    has_key_data: bool
    has_suggestion: bool
    bullet_lines: int
    warning_count: int
    source_count: int


def ensure_dirs() -> None:
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)


def screenshot(page, name: str) -> str:
    path = SCREENSHOT_DIR / f"{name}-{int(time.time() * 1000)}.png"
    page.screenshot(path=str(path), full_page=False)
    return str(path)


def goto_ready(page, path: str) -> None:
    page.goto(f"{BASE_URL}{path}", wait_until="domcontentloaded", timeout=30000)
    try:
        page.wait_for_load_state("networkidle", timeout=7000)
    except PlaywrightTimeoutError:
        pass


def login(page) -> None:
    goto_ready(page, "/login")
    page.locator("input[type='email']").first.fill(EMAIL)
    page.locator("input[type='password']").first.fill(PASSWORD)
    page.get_by_role("button").filter(has_text="登录").first.click()
    page.wait_for_url(f"{BASE_URL}/", timeout=30000)


def open_ai(page) -> None:
    goto_ready(page, "/ai")
    page.get_by_role("heading", name="AI 助手").wait_for(timeout=20000)


def pick_mode(page, label: str) -> None:
    page.get_by_role("button", name=label).click()
    page.wait_for_timeout(250)


def ask_question(page, mode: str, question: str) -> QAResult:
    assistant_bubbles = page.locator("div.flex.justify-start > div.max-w-\\[80\\%\\]")
    before = assistant_bubbles.count()
    start = time.time()

    input_box = page.locator("input[type='text']").first
    input_box.fill(question)
    page.get_by_role("button", name="发送").click()

    page.wait_for_function(
        "({ selector, beforeCount }) => document.querySelectorAll(selector).length > beforeCount",
        arg={"selector": "div.flex.justify-start > div.max-w-\\[80\\%\\]", "beforeCount": before},
        timeout=120000,
    )

    # wait a little for the final metadata panel to settle after streaming
    page.wait_for_timeout(600)
    after = assistant_bubbles.count()
    bubble = assistant_bubbles.nth(after - 1)
    answer = bubble.inner_text(timeout=10000).strip()

    warning_count = bubble.locator("div:has-text('提示') li").count()
    source_count = bubble.locator("div:has-text('来源') li").count()
    lines = [line.strip() for line in answer.splitlines() if line.strip()]
    bullet_lines = sum(1 for line in lines if line.startswith("-"))

    return QAResult(
        mode=mode,
        question=question,
        answer=answer,
        elapsed_ms=int((time.time() - start) * 1000),
        answer_len=len(answer),
        has_conclusion=("结论" in answer),
        has_key_data=("关键数据" in answer),
        has_suggestion=("建议" in answer),
        bullet_lines=bullet_lines,
        warning_count=warning_count,
        source_count=source_count,
    )


def aggregate(results: list[QAResult]) -> dict[str, Any]:
    by_mode: dict[str, list[QAResult]] = {}
    for item in results:
        by_mode.setdefault(item.mode, []).append(item)

    summary: dict[str, Any] = {}
    for mode, rows in by_mode.items():
        count = len(rows)
        summary[mode] = {
            "count": count,
            "avg_answer_len": round(sum(r.answer_len for r in rows) / max(1, count), 1),
            "avg_elapsed_ms": round(sum(r.elapsed_ms for r in rows) / max(1, count), 1),
            "structured_rate": round(
                sum(1 for r in rows if r.has_conclusion and r.has_key_data and r.has_suggestion) / max(1, count), 3
            ),
            "avg_warning_count": round(sum(r.warning_count for r in rows) / max(1, count), 3),
            "avg_source_count": round(sum(r.source_count for r in rows) / max(1, count), 3),
        }
    return summary


def main() -> int:
    ensure_dirs()
    started = int(time.time())
    results: list[QAResult] = []
    mode_shots: dict[str, str] = {}

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN", ignore_https_errors=True)
        page = context.new_page()

        login(page)

        for mode in MODES:
            open_ai(page)
            pick_mode(page, mode)
            for question in QUESTIONS:
                results.append(ask_question(page, mode, question))
            mode_shots[mode] = screenshot(page, f"ai-mode-{mode.lower()}")

        context.close()
        browser.close()

    payload = {
        "base_url": BASE_URL,
        "started_at_epoch": started,
        "finished_at_epoch": int(time.time()),
        "questions": QUESTIONS,
        "modes": MODES,
        "summary": aggregate(results),
        "mode_screenshots": mode_shots,
        "results": [asdict(item) for item in results],
    }
    REPORT_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"REPORT={REPORT_PATH}")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
