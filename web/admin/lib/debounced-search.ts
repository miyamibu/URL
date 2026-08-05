export const USER_SEARCH_DEBOUNCE_MS = 250;

export type AsyncSearchTask<T> = (signal: AbortSignal) => Promise<T>;

export type DebouncedSearchController<T> = {
  schedule(task: AsyncSearchTask<T>): void;
  cancel(): void;
};

function isAbortError(error: unknown): boolean {
  return Boolean(error && typeof error === "object" && "name" in error && error.name === "AbortError");
}

export function createDebouncedSearchController<T>(
  delayMs: number,
  onResult: (result: T) => void,
  onError: (error: unknown) => void,
): DebouncedSearchController<T> {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let activeController: AbortController | null = null;
  let generation = 0;

  function cancel() {
    generation += 1;
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
    activeController?.abort();
    activeController = null;
  }

  function schedule(task: AsyncSearchTask<T>) {
    cancel();
    const taskGeneration = generation;
    timer = setTimeout(() => {
      timer = null;
      if (taskGeneration !== generation) return;
      const controller = new AbortController();
      activeController = controller;
      void (async () => {
        try {
          const result = await task(controller.signal);
          if (taskGeneration === generation) onResult(result);
        } catch (error) {
          if (taskGeneration === generation && !isAbortError(error)) onError(error);
        } finally {
          if (taskGeneration === generation) activeController = null;
        }
      })();
    }, delayMs);
  }

  return { schedule, cancel };
}
