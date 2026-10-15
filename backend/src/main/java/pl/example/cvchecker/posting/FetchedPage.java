package pl.example.cvchecker.posting;

import java.net.URI;

/** A page returned by the static fetcher (docs/API.md §4.1): the final URL after redirects and its HTML. */
public record FetchedPage(URI uri, String html) {
}
