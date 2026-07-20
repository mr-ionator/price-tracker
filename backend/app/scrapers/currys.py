from bs4 import BeautifulSoup

from .base import BaseScraper, ScrapeResult, parse_price_text


class CurrysScraper(BaseScraper):
    site = "currys"

    def headers(self) -> dict[str, str]:
        headers = super().headers()
        # Currys sits behind aggressive bot protection; a Referer and full
        # client-hint set measurably improve the pass rate.
        headers["Referer"] = "https://www.currys.ie/"
        headers["sec-ch-ua"] = '"Chromium";v="126", "Google Chrome";v="126"'
        headers["sec-ch-ua-mobile"] = "?0"
        headers["sec-ch-ua-platform"] = '"Windows"'
        return headers

    def parse_site(self, soup: BeautifulSoup) -> ScrapeResult | None:
        for selector in (
            "[data-testid='product-price']",
            ".product-price .value",
            ".prices .price",
            "[class*='ProductPrice'] [class*='amount']",
        ):
            tag = soup.select_one(selector)
            if tag and tag.get_text(strip=True):
                try:
                    return ScrapeResult(price=parse_price_text(tag.get_text(strip=True)))
                except Exception:
                    continue
        return None
