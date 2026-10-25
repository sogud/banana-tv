package com.sogud.bananatv.media;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Translate CatVod's legacy response tuple without corrupting binary bodies or HTTP framing. */
final class MediaProxy {
    private MediaProxy() { }

    static Response response(Object[] value) {
        if (value != null && value.length > 0 && value[0] instanceof Response) return (Response) value[0];
        if (value == null || value.length < 3)
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
        int code = ((Number) value[0]).intValue();
        Response.IStatus status = Response.Status.lookup(code);
        if (status == null) {
            final int custom = code >= 100 && code <= 599 ? code : 500;
            status = new Response.IStatus() {
                @Override public int getRequestStatus() { return custom; }
                @Override public String getDescription() { return custom + " Source Response"; }
            };
        }
        InputStream body;
        if (value[2] instanceof InputStream) body = (InputStream) value[2];
        else if (value[2] instanceof byte[]) body = new ByteArrayInputStream((byte[]) value[2]);
        else body = new ByteArrayInputStream(value[2] == null ? new byte[0] : value[2].toString().getBytes(StandardCharsets.UTF_8));
        Response response = NanoHTTPD.newChunkedResponse(status, value[1].toString(), body);
        if (value.length > 3 && value[3] instanceof Map) {
            for (Map.Entry<?, ?> header : ((Map<?, ?>) value[3]).entrySet()) {
                String name = header.getKey().toString();
                if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) continue;
                response.addHeader(name, header.getValue().toString());
            }
        }
        return response;
    }
}
