"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type ActionKey = string | number;

/**
 * Serialises user-triggered async work by key.
 *
 * The ref is the source of truth so two clicks in the same render frame cannot
 * start the same mutation twice. State is kept separately for loading/disabled
 * feedback in the UI.
 */
export function useAsyncAction<Key extends ActionKey = string>() {
  const locksRef = useRef(new Set<Key>());
  const mountedRef = useRef(true);
  const [runningKeys, setRunningKeys] = useState<ReadonlySet<Key>>(() => new Set());

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const publish = useCallback(() => {
    if (mountedRef.current) {
      setRunningKeys(new Set(locksRef.current));
    }
  }, []);

  const run = useCallback(async <Result,>(key: Key, action: () => Promise<Result>): Promise<Result | undefined> => {
    if (locksRef.current.has(key)) return undefined;

    locksRef.current.add(key);
    publish();
    try {
      return await action();
    } finally {
      locksRef.current.delete(key);
      publish();
    }
  }, [publish]);

  const isRunning = useCallback((key: Key) => runningKeys.has(key), [runningKeys]);

  return {
    run,
    isRunning,
    isAnyRunning: runningKeys.size > 0,
  };
}
