package com.tpverp.saas.mail;

import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

final class CommunicationHtml {
    private static final Cleaner CLEANER = new Cleaner(new Safelist()
            .addTags("p","div","br","strong","b","em","i","u","s","h1","h2","h3","ul","ol","li","blockquote",
                    "pre","code","table","thead","tbody","tr","th","td","hr","span","a")
            .addAttributes("a","href").addProtocols("a","href","https","http","mailto"));

    private CommunicationHtml() {}

    static Communications.Body sanitize(String raw) {
        if (raw == null || raw.length() > 100000 || raw.indexOf(0) >= 0) throw Communications.invalid();
        var doc = CLEANER.clean(Jsoup.parseBodyFragment(raw));
        doc.outputSettings().prettyPrint(false);
        var body = doc.body();
        String html = body.html();
        if (html.length() > 100000) throw Communications.invalid();
        // Keep link destinations and block boundaries available to text-only mail readers.
        body.select("a[href]").forEach(link -> {
            if (!link.text().equals(link.attr("href"))) link.appendText(" (" + link.attr("href") + ")");
        });
        body.select("br").forEach(br -> br.after(new org.jsoup.nodes.TextNode("\n")));
        body.select("p,div,h1,h2,h3,li,blockquote,pre,tr,hr").forEach(block -> block.appendText("\n"));
        return new Communications.Body(html, body.wholeText().strip());
    }
}
