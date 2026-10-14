package pl.example.cvchecker.llm;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Response;

import pl.example.cvchecker.analysis.RunHandle;

/**
 * Registers every blocking LLM HTTP call with the run that issued it (docs/API.md §1.1). OkHttp
 * executes a synchronous {@code Call} on the calling (pipeline worker) thread, which publishes its
 * {@link RunHandle} via a {@code ThreadLocal}, so {@code /cancel} can abort the in-flight call —
 * a thread interrupt alone does not break a blocking socket read (docs/DECISIONS.md).
 */
public class LlmCallInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        RunHandle handle = RunHandle.current();
        if (handle == null) {
            return chain.proceed(chain.request());
        }
        okhttp3.Call call = chain.call();
        handle.register(call);
        try {
            return chain.proceed(chain.request());
        } finally {
            handle.unregister(call);
        }
    }
}
