package com.kakaotalkbot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factories for bubble components and common objects. Every method returns a
 * plain {@code Map} so that fields the library does not model can still be added.
 */
public final class Components {
    private Components() {}

    /** Build a map from alternating key/value pairs; null values are skipped. */
    public static Map<String, Object> map(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("map() needs an even number of arguments");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return m;
    }

    private static Map<String, Object> wrap(String key, Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, body);
        return m;
    }

    // -- bubbles ----------------------------------------------------------

    /** Text only (max 1,000 chars). Use {@link #mentionTag(String)} to mention a user. */
    public static Map<String, Object> simpleText(String text) {
        return wrap("simpleText", map("text", text));
    }

    public static Map<String, Object> simpleImage(String imageUrl, String altText) {
        return wrap("simpleImage", map("imageUrl", imageUrl, "altText", altText));
    }

    public static Map<String, Object> textCard(
            String title, String description, List<Map<String, Object>> buttons, String buttonLayout) {
        return wrap("textCard", map(
                "title", title, "description", description, "buttons", buttons, "buttonLayout", buttonLayout));
    }

    public static Map<String, Object> basicCard(
            String title, String description, Map<String, Object> thumbnail,
            List<Map<String, Object>> buttons, String buttonLayout) {
        return wrap("basicCard", map(
                "title", title, "description", description, "thumbnail", thumbnail,
                "buttons", buttons, "buttonLayout", buttonLayout));
    }

    public static Map<String, Object> listCard(
            Map<String, Object> header, List<Map<String, Object>> items,
            List<Map<String, Object>> buttons, String buttonLayout) {
        return wrap("listCard", map(
                "header", header, "items", items, "buttons", buttons, "buttonLayout", buttonLayout));
    }

    /** ItemCard from raw fields (itemList, title, description, head/profile, buttons, ...). */
    public static Map<String, Object> itemCard(Map<String, Object> fields) {
        if (fields.containsKey("head") && fields.containsKey("profile")) {
            throw new IllegalArgumentException("ItemCard cannot have both head and profile");
        }
        return wrap("itemCard", fields);
    }

    /** CommerceCard from raw fields; {@code currency} is forced to {@code won}. */
    public static Map<String, Object> commerceCard(Map<String, Object> fields) {
        Map<String, Object> f = new LinkedHashMap<>(fields);
        f.put("currency", "won");
        return wrap("commerceCard", f);
    }

    /** Carousel (channel chat only). type: basicCard | commerceCard | listCard | itemCard | textCard */
    public static Map<String, Object> carousel(String type, List<Map<String, Object>> items, Map<String, Object> header) {
        List<Map<String, Object>> list = new ArrayList<>(items);
        return wrap("carousel", map("type", type, "items", list, "header", header));
    }

    // -- common objects ---------------------------------------------------

    public static Map<String, Object> thumbnail(String imageUrl) {
        return thumbnail(imageUrl, null, null, null);
    }

    public static Map<String, Object> thumbnail(
            String imageUrl, Map<String, Object> link, Boolean fixedRatio, String altText) {
        return map("imageUrl", imageUrl, "link", link, "fixedRatio", fixedRatio, "altText", altText);
    }

    public static Map<String, Object> link(String mobile, String pc, String web) {
        return map("mobile", mobile, "pc", pc, "web", web);
    }

    public static Map<String, Object> profile(String nickname, String imageUrl) {
        return map("nickname", nickname, "imageUrl", imageUrl);
    }

    public static Map<String, Object> listItem(String title, String description, String imageUrl, Map<String, Object> link) {
        return map("title", title, "description", description, "imageUrl", imageUrl, "link", link);
    }

    public static Map<String, Object> carouselHeader(String title, String description, Map<String, Object> thumbnail) {
        return map("title", title, "description", description, "thumbnail", thumbnail);
    }

    /** Placeholder for a mention inside SimpleText: {@code {{#mentions.<userKey>}}}. */
    public static String mentionTag(String userKey) {
        return "{{#mentions." + userKey + "}}";
    }
}
