package pl.example.cvchecker.posting;

import java.net.URI;
import java.util.Optional;

/**
 * Optional headless-browser step in the posting extraction ladder (docs/API.md §4.1 step 2). Returns
 * the rendered DOM HTML, or {@link Optional#empty()} when rendering is disabled or unavailable so the
 * resolver can fail gracefully.
 */
public interface PostingRenderer {

    Optional<String> render(URI uri);
}
