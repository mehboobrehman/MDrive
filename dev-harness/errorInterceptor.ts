import { logToAgent } from './logger';

declare const __DEV__: boolean | undefined;

let isInterceptorAttached = false;

export function setupErrorInterceptor(options?: { force?: boolean }) {
    const isDev = typeof __DEV__ !== 'undefined' ? __DEV__ : true;
    const shouldEnable = options?.force || isDev;

    if (!shouldEnable || isInterceptorAttached) {
        return;
    }

    isInterceptorAttached = true;

    // React Native Error Handler
    try {
        const globalAny = global as any;
        if (globalAny && globalAny.ErrorUtils && typeof globalAny.ErrorUtils.setGlobalHandler === 'function') {
            const originalHandler = globalAny.ErrorUtils.getGlobalHandler();
            globalAny.ErrorUtils.setGlobalHandler((error: any, isFatal?: boolean) => {
                logToAgent(
                    isFatal ? 'FATAL' : 'ERROR',
                    error?.message || String(error),
                    error?.stack,
                    { fatal: isFatal, platform: 'react-native' }
                ).catch(() => {});

                if (originalHandler) {
                    originalHandler(error, isFatal);
                }
            });
        }
    } catch (_) {
        // Fallback or ignore if not in react-native environment
    }

    // Web / Browser Error Handlers
    try {
        if (typeof window !== 'undefined') {
            // Handle window.onerror
            const originalOnError = window.onerror;
            window.onerror = (message, source, lineno, colno, error) => {
                logToAgent(
                    'ERROR',
                    String(message),
                    error?.stack,
                    { source, lineno, colno, platform: 'web' }
                ).catch(() => {});

                if (originalOnError) {
                    return originalOnError(message, source, lineno, colno, error);
                }
                return false;
            };

            // Handle unhandled promise rejections
            window.addEventListener('unhandledrejection', (event) => {
                const reason = event.reason;
                const message = reason?.message || String(reason || 'Unhandled Promise Rejection');
                const stack = reason?.stack || null;

                logToAgent(
                    'ERROR',
                    `Unhandled Promise Rejection: ${message}`,
                    stack,
                    { type: 'unhandledrejection', platform: 'web' }
                ).catch(() => {});
            });
        }
    } catch (_) {
        // Fallback or ignore if not in browser environment
    }
}
