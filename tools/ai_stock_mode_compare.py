from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

BASE_URL = "http://localhost:33000"
EMAIL = "test@mamoji.com"
PASSWORD = "123456"

REPORT_PATH = Path("D:/projects/shuai/mamoji/.codex-smoke/ai-stock-mode-compare.json")
SCREENSHOT_DIR = Path("D:/projects/shuai/mamoji/.codex-smoke/screenshots")

QUESTIONS = [
    "消费板块近期怎么样？",
    "半导体板块今天怎么看，短线风险点是什么？",
    "上证指数现在更适合追涨还是观望？",
    "新能源板块最近有没有回暖迹象？",
]
MODES = ["Auto", "Agent", "LLM"]
UNAVAILABLE_HINTS = [
    "模型服务暂不可用",
    "服务暂时不可用",
    "AI 服务暂时不可用",
    "sorry, ai service is temporarily unavailable",
]


@dataclass
class QAResult:
    mode: str
    question: str
    answer: str
    elapsed_ms: int
    answer_len: int
    has_risk_warning: bool
    has_unavailable_hint: bool
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


def open_stock_assistant(page) -> None:
    goto_ready(page, "/ai")
    page.get_by_role("heading", name="AI 助手").wait_for(timeout=20000)
    page.get_by_role("button", name="股票助手").first.click()
    page.wait_for_timeout(250)


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
    page.wait_for_timeout(600)

    after = assistant_bubbles.count()
    bubble = assistant_bubbles.nth(after - 1)
    answer = bubble.inner_text(timeout=10000).strip()
    answer_lower = answer.lower()

    warning_count = bubble.locator("div:has-text('提示') li").count()
    source_count = bubble.locator("div:has-text('来源') li").count()

    return QAResult(
        mode=mode,
        question=question,
        answer=answer,
        elapsed_ms=int((time.time() - start) * 1000),
        answer_len=len(answer),
        has_risk_warning=("投资有风险" in answer),
        has_unavailable_hint=any(hint in answer_lower for hint in UNAVAILABLE_HINTS),
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
            "risk_warning_rate": round(sum(1 for r in rows if r.has_risk_warning) / max(1, count), 3),
            "unavailable_hint_count": sum(1 for r in rows if r.has_unavailable_hint),
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
        open_stock_assistant(page)

        for mode in MODES:
            open_stock_assistant(page)
            pick_mode(page, mode)
            for question in QUESTIONS:
                results.append(ask_question(page, mode, question))
            mode_shots[mode] = screenshot(page, f"ai-stock-mode-{mode.lower()}")

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
