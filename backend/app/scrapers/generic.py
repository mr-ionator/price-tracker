from .base import BaseScraper


class GenericScraper(BaseScraper):
    """Relies entirely on the shared JSON-LD and metadata fallbacks."""

    site = "generic"
