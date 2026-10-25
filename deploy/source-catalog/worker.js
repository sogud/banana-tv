export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const prefix = `/${env.SOURCE_TOKEN}/`;
    let path;
    try {
      path = decodeURIComponent(url.pathname.slice(prefix.length - 1));
    } catch {
      path = '';
    }
    const asset = path.startsWith('/assets/') && !path.includes('\\') && !path.includes('\0')
      && !path.split('/').some(segment => segment === '.' || segment === '..');
    if (!env.SOURCE_TOKEN || !url.pathname.startsWith(prefix) || (path !== '/sources.json' && !asset)) {
      return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store' } });
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
    }
    url.pathname = path;
    url.search = '';
    const result = await env.ASSETS.fetch(new Request(url, request));
    const response = new Response(result.body, result);
    response.headers.set('Referrer-Policy', 'no-referrer');
    return response;
  }
};
