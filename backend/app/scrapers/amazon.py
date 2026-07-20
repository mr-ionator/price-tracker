from bs4 import BeautifulSoup

from .base import BaseScraper, ScrapeError, ScrapeResult, parse_price_text


class AmazonScraper(BaseScraper):
    site = "amazon"

    def parse_site(self, soup: BeautifulSoup) -> ScrapeResult | None:
        # Amazon serves an interstitial to suspected bots; detect it explicitly
        # so the stored error is actionable rather than "no price found".
        if soup.find("form", action=lambda a: a and "validateCaptcha" in a):
            raise ScrapeError("amazon served a captcha page; retry later")

        # Primary buy-box price: hidden accessibility span holds the full value.
        for container_id in ("corePriceDisplay_desktop_feature_div", "corePrice_feature_div"):
            container = soup.find(id=container_id)
            if not container:
                continue
            offscreen = container.select_one(".a-price .a-offscreen")
            if offscreen and offscreen.get_text(strip=True):
                return ScrapeResult(price=parse_price_text(offscreen.get_text(strip=True)))
            whole = container.select_one(".a-price-whole")
            fraction = container.select_one(".a-price-fraction")
            if whole:
                text = whole.get_text(strip=True).rstrip(".,")
                if fraction:
                    text += "." + fraction.get_text(strip=True)
                return ScrapeResult(price=parse_price_text(text))

        offscreen = soup.select_one("#apex_desktop .a-price .a-offscreen")
        if offscreen and offscreen.get_text(strip=True):
            return ScrapeResult(price=parse_price_text(offscreen.get_text(strip=True)))
        return None
