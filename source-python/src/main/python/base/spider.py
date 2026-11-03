"""Independent adapter host for the public Python source callback protocol."""


class Spider:
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        return {"class": []}

    def homeVideoContent(self):
        return {"list": []}

    def searchContent(self, key, quick, pg="1"):
        return {"list": []}

    def localProxy(self, params):
        return None

    def getProxyUrl(self, local=True):
        return self._banana_proxy

    def destroy(self):
        pass
