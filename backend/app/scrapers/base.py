import json
import re
from dataclasses import dataclass

import httpx
from bs4 import BeautifulSoup

from .. import config


class ScrapeError(Exception):
    pass


@dataclass
class ScrapeResult:
    price: float
    currency: str = "EUR"


_PRICE_RE = re.compile(r"(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+)")


def parse_price_text(text: str) -> float:
    """Parse a localized price string like '€1.299,99', '1,299.99' or '149.00'."""
    match = _PRICE_RE.search(text.replace("\xa0", " "))
    if not match:
        raise ScrapeError(f"no price found in text: {text!r}")
    raw = match.group(1)
    # Decide which separator is decimal: the last one, if followed by <= 2 digits.
    last_sep = max(raw.rfind(","), raw.rfind("."))
    if last_sep == -1:
        return float(raw)
    decimals = raw[last_sep + 1 :]
    integer = re.sub(r"[.,]", "", raw[:last_sep])
    if len(decimals) <= 2:
        return float(f"{integer}.{decimals}")
    return float(re.sub(r"[.,]", "", raw))


def _iter_jsonld_nodes(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _iter_jsonld_nodes(value)
    elif isinstance(node, list):
        for item in node:
            yield from _iter_jsonld_nodes(item)


def extract_jsonld_price(soup: BeautifulSoup) -> ScrapeResult | None:
    """Find a schema.org Offer price in any ld+json block on the page."""
    for script in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(script.string or "")
        except (json.JSONDecodeError, TypeError):
            continue
        for node in _iter_jsonld_nodes(data):
            node_type = node.get("@type", "")
            types = node_type if isinstance(node_type, list) else [node_type]
            if not any(t in ("Offer", "AggregateOffer") for t in types):
                continue
            price = node.get("price") or node.get("lowPrice")
            if price in (None, "", 0, "0", 0.0):
                continue
            try:
                value = float(str(price).replace(",", "."))
            except ValueError:
                continue
            if value <= 0:
                continue
            currency = str(node.get("priceCurrency") or "EUR")
            return ScrapeResult(price=value, currency=currency)
    return None


def extract_meta_price(soup: BeautifulSoup) -> ScrapeResult | None:
    """Fallbacks: itemprop/OpenGraph/twitter price metadata."""
    candidates = [
        {"itemprop": "price"},
        {"property": "product:price:amount"},
        {"property": "og:price:amount"},
        {"name": "twitter:data1"},
    ]
    for attrs in candidates:
        for tag in soup.find_all(["meta", "span", "div"], attrs=attrs):
            raw = tag.get("content") or tag.get_text(strip=True)
            if not raw:
                continue
            try:
                return ScrapeResult(price=parse_price_text(raw))
            except ScrapeError:
                continue
    return None


class BaseScraper:
    site = "generic"

    def headers(self) -> dict[str, str]:
        return {
            "User-Agent": config.USER_AGENT,
            "Accept": (
                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                "image/avif,image/webp,*/*;q=0.8"
            ),
            "Accept-Language": "en-IE,en-GB;q=0.9,en;q=0.8",
            "Accept-Encoding": "gzip, deflate, br",
            "Upgrade-Insecure-Requests": "1",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
        }

    async def fetch(self, url: str) -> str:
        async with httpx.AsyncClient(
            headers=self.headers(),
            follow_redirects=True,
            timeout=config.HTTP_TIMEOUT_SECONDS,
        ) as client:
            response = await client.get(url)
            if response.status_code != 200:
                raise ScrapeError(f"HTTP {response.status_code} from {url}")
            return response.text

    def parse(self, html: str) -> ScrapeResult:
        soup = BeautifulSoup(html, "html.parser")
        result = self.parse_site(soup) or extract_jsonld_price(soup) or extract_meta_price(soup)
        if result is None:
            raise ScrapeError(f"could not extract a price ({self.site})")
        return result

    def parse_site(self, soup: BeautifulSoup) -> ScrapeResult | None:
        """Site-specific selectors; subclasses override. None -> use fallbacks."""
        return None

    async def scrape(self, url: str) -> ScrapeResult:
        html = await self.fetch(url)
        return self.parse(html)
