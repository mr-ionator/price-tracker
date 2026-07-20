from urllib.parse import urlparse

from .base import BaseScraper, ScrapeError, ScrapeResult
from .amazon import AmazonScraper
from .currys import CurrysScraper
from .paradigit import ParadigitScraper
from .generic import GenericScraper

_SCRAPERS: dict[str, BaseScraper] = {
    "amazon": AmazonScraper(),
    "paradigit": ParadigitScraper(),
    "currys": CurrysScraper(),
    "generic": GenericScraper(),
}


def site_for_url(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    if "amazon." in host:
        return "amazon"
    if "paradigit." in host:
        return "paradigit"
    if "currys." in host:
        return "currys"
    return "generic"


def scraper_for_site(site: str) -> BaseScraper:
    return _SCRAPERS.get(site, _SCRAPERS["generic"])


__all__ = [
    "BaseScraper",
    "ScrapeError",
    "ScrapeResult",
    "site_for_url",
    "scraper_for_site",
]
