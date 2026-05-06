import type CouncilMember from 'Frontend/generated/dev/council/model/CouncilMember';

/**
 * Options for subscribeToFlux
 */
export interface SubscribeToFluxOptions {
  /** Timeout in milliseconds before resolving with collected items (default: 120000) */
  timeout?: number;
  /** Label for logging (default: 'unknown') */
  label?: string;
}

/**
 * Wraps a Vaadin Hilla Flux subscription into a Promise that collects items
 * and calls onItem with a snapshot after each emission.
 *
 * Includes timeout protection to prevent the Promise from hanging indefinitely
 * if the onComplete callback never fires (e.g., due to Hilla/WebSocket issues).
 */
export function subscribeToFlux<T>(
  subscription: {
    onNext(cb: (item: T) => void): any;
    onComplete(cb: () => void): any;
    onError(cb: (err: string) => void): any;
  },
  onItem: (items: T[]) => void,
  options?: SubscribeToFluxOptions
): Promise<T[]> {
  const items: T[] = [];
  const label = options?.label || 'unknown';
  const timeout = options?.timeout || 120000;

  console.log(`[subscribeToFlux:${label}] Starting subscription`);

  return new Promise<T[]>((resolve, reject) => {
    let completed = false;
    let timeoutId: ReturnType<typeof setTimeout> | null = null;

    const cleanup = () => {
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
      }
    };

    // Timeout protection - resolve with collected items rather than hanging forever
    timeoutId = setTimeout(() => {
      if (!completed) {
        console.error(`[subscribeToFlux:${label}] TIMEOUT after ${timeout}ms. Items collected: ${items.length}`);
        completed = true;
        // Graceful degradation: resolve with collected items instead of rejecting
        if (items.length > 0) {
          console.warn(`[subscribeToFlux:${label}] Resolving with ${items.length} items despite timeout`);
          resolve(items);
        } else {
          reject(new Error(`Subscription timeout after ${timeout}ms with no items received`));
        }
      }
    }, timeout);

    try {
      subscription
        .onNext((item: T) => {
          try {
            console.log(`[subscribeToFlux:${label}] onNext #${items.length + 1}`);
            items.push(item);
            onItem([...items]);
          } catch (err) {
            console.error(`[subscribeToFlux:${label}] Error in onNext callback:`, err);
          }
        })
        .onComplete(() => {
          console.log(`[subscribeToFlux:${label}] onComplete. Total items: ${items.length}`);
          if (!completed) {
            completed = true;
            cleanup();
            resolve(items);
          }
        })
        .onError((error: string) => {
          console.error(`[subscribeToFlux:${label}] onError:`, error);
          if (!completed) {
            completed = true;
            cleanup();
            reject(new Error(error));
          }
        });
    } catch (err) {
      console.error(`[subscribeToFlux:${label}] Subscription setup error:`, err);
      cleanup();
      reject(err);
    }
  });
}

const DEFAULT_COLOR = '#6b7280';

/**
 * Resolves avatar color from the backend-provided council members list,
 * eliminating hardcoded color maps in each panel.
 */
export function getAvatarColor(modelId: string | undefined, members: CouncilMember[]): string {
  if (!modelId) return DEFAULT_COLOR;
  return members.find(m => m.modelId === modelId)?.avatarColor ?? DEFAULT_COLOR;
}
