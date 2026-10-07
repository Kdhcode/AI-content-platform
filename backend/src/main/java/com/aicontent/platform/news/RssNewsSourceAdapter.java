package com.aicontent.platform.news;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Generic RSS 2.0 / Atom reader. It is provider-neutral on purpose: it does not decide <em>which</em> feeds to use
 * (that is an OPEN ITEM); a feed is configured as {@code news_source.base_url}. Per-source options in
 * {@code config}: {@code publisher} overrides the publisher name (default: channel title, then host name).
 *
 * <p>XML is parsed with DOCTYPE declarations disabled (no XXE / entity expansion).
 */
public class RssNewsSourceAdapter implements NewsSourceAdapter {

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final HttpClient client;
    private final Duration timeout;

    public RssNewsSourceAdapter(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public NewsSourceType type() {
        return NewsSourceType.RSS;
    }

    @Override
    public List<CollectedArticle> fetchLatest(NewsSource source, int limit) throws SourceFetchException {
        if (source.baseUrl() == null || source.baseUrl().isBlank()) {
            throw new SourceFetchException(SourceFetchException.Kind.UNSUPPORTED, "RSS source has no base_url (feed url)");
        }
        byte[] body = download(source.baseUrl());
        Document doc = parse(body);
        return extract(doc, source, limit);
    }

    private byte[] download(String url) throws SourceFetchException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", "ai-content-platform/0.1 (+rss-adapter)")
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new SourceFetchException(SourceFetchException.Kind.UNSUPPORTED, "invalid feed url: " + url, e);
        }
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new SourceFetchException(SourceFetchException.Kind.TIMEOUT, "timeout fetching " + url, e);
        } catch (IOException e) {
            throw new SourceFetchException(SourceFetchException.Kind.NETWORK, "network error fetching " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceFetchException(SourceFetchException.Kind.NETWORK, "interrupted while fetching " + url, e);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new SourceFetchException(SourceFetchException.Kind.RATE_LIMITED, "HTTP 429 from " + url);
        }
        if (status >= 500) {
            throw new SourceFetchException(SourceFetchException.Kind.SERVER_ERROR, "HTTP " + status + " from " + url);
        }
        if (status < 200 || status >= 300) {
            throw new SourceFetchException(SourceFetchException.Kind.CLIENT_ERROR, "HTTP " + status + " from " + url);
        }
        return response.body();
    }

    private Document parse(byte[] body) throws SourceFetchException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new SourceFetchException(SourceFetchException.Kind.PARSE_ERROR, "feed is not valid XML: " + e.getMessage(), e);
        }
    }

    private List<CollectedArticle> extract(Document doc, NewsSource source, int limit) throws SourceFetchException {
        Element root = doc.getDocumentElement();
        String rootName = localName(root);
        List<Element> entries = new ArrayList<>();
        String channelTitle = null;
        if (rootName.equals("rss") || rootName.equals("RDF")) {
            NodeList items = doc.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                entries.add((Element) items.item(i));
            }
            Element channel = firstChild(root, "channel");
            channelTitle = channel == null ? null : childText(channel, "title");
        } else if (rootName.equals("feed")) {
            NodeList items = doc.getElementsByTagNameNS("*", "entry");
            for (int i = 0; i < items.getLength(); i++) {
                entries.add((Element) items.item(i));
            }
            channelTitle = childText(root, "title");
        } else {
            throw new SourceFetchException(SourceFetchException.Kind.PARSE_ERROR, "unrecognised feed root element <" + rootName + ">");
        }

        String publisher = source.configValue("publisher", firstNonBlank(channelTitle, hostOf(source.baseUrl())));
        List<CollectedArticle> out = new ArrayList<>();
        for (Element e : entries) {
            if (out.size() >= limit) {
                break;
            }
            String link = rootName.equals("feed") ? atomLink(e) : childText(e, "link");
            String published = firstNonBlank(childText(e, "pubDate"), childText(e, "published"), childText(e, "updated"), childText(e, "date"));
            String author = firstNonBlank(childText(e, "creator"), childText(e, "author"));
            String category = childText(e, "category");
            String body = firstNonBlank(childText(e, "encoded"), childText(e, "content"), childText(e, "description"), childText(e, "summary"));
            out.add(new CollectedArticle(
                    clean(childText(e, "title")),
                    link == null ? null : link.trim(),
                    publisher,
                    clean(author),
                    clean(category),
                    published == null ? null : published.trim(),
                    clean(body)));
        }
        return out;
    }

    private static String atomLink(Element entry) {
        NodeList links = entry.getElementsByTagNameNS("*", "link");
        String fallback = null;
        for (int i = 0; i < links.getLength(); i++) {
            Element l = (Element) links.item(i);
            String href = l.getAttribute("href");
            if (href == null || href.isBlank()) {
                continue;
            }
            String rel = l.getAttribute("rel");
            if (rel == null || rel.isBlank() || rel.equals("alternate")) {
                return href;
            }
            fallback = href;
        }
        return fallback;
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static Element firstChild(Element parent, String name) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && localName(el).equals(name)) {
                return el;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element el = firstChild(parent, name);
        return el == null ? null : el.getTextContent();
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String noTags = TAGS.matcher(raw).replaceAll(" ");
        String unescaped = noTags.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        String collapsed = SPACES.matcher(unescaped).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host;
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }
}
