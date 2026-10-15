package pl.example.cvchecker.posting;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import org.springframework.stereotype.Component;

/**
 * Extracts readable text from a static HTML page (docs/API.md §4.1). Scripts, styles, navigation and
 * other chrome are dropped; {@code main}/{@code article} is preferred over the whole body. This is a
 * best-effort first pass — the cleanup LLM call refines it when the result is used.
 */
@Component
public class HtmlTextExtractor {

    private static final String CHROME_SELECTOR = "script, style, noscript, template, svg, iframe, "
            + "nav, header, footer, aside, form, button";

    public String extract(String html) {
        Document document = Jsoup.parse(html);
        document.select(CHROME_SELECTOR).remove();

        Element root = document.selectFirst("main");
        if (root == null) root = document.selectFirst("article");
        if (root == null) root = document.body();
        if (root == null) return "";
        return root.text().strip();
    }
}
