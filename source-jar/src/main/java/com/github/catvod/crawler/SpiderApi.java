package com.github.catvod.crawler;

import com.google.gson.JsonArray;

/** Legacy binary contract; callback objects stay in the caller's process. */
public interface SpiderApi {
    String getAddress(boolean local);
    String getPort();
    void log(String message);
    String multiReq(JsonArray requests);
    String webParse(String url, String headers);
}
