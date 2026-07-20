from bs4 import BeautifulSoup

from .base import BaseScraper, ScrapeResult, parse_price_text


class ParadigitScraper(BaseScraper):
    site = "paradigit"

    def parse_site(self, soup: BeautifulSoup) -> ScrapeResult | None:
        # Paradigit ships schema.org JSON-LD with the offer price, which the
        # shared fallback handles; these selectors cover the visible price
        # block in case the JSON-LD is missing on some templates.
        for selector in (
            "[data-testid='product-price']",
            ".product-price .price",
            ".price-current",
        ):
            tag = soup.select_one(selector)
            if tag and tag.get_text(strip=True):
                try:
                    return ScrapeResult(price=parse_price_text(tag.get_text(strip=True)))
                except Exception:
                    continue
        return None
