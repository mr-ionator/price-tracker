"""Application configuration loaded from environment variables."""
import os


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:////data/pricetracker.db")

# How often the background job re-checks every tracked URL.
CHECK_INTERVAL_MINUTES = _int_env("CHECK_INTERVAL_MINUTES", 60)

# Delay between two consecutive scrapes inside one check run, to stay polite.
SCRAPE_DELAY_SECONDS = _int_env("SCRAPE_DELAY_SECONDS", 5)

HTTP_TIMEOUT_SECONDS = _int_env("HTTP_TIMEOUT_SECONDS", 30)

# --- Email (SMTP) ---
SMTP_HOST = os.environ.get("SMTP_HOST", "")
SMTP_PORT = _int_env("SMTP_PORT", 587)
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("SMTP_FROM", SMTP_USER)
SMTP_STARTTLS = os.environ.get("SMTP_STARTTLS", "true").lower() != "false"
ALERT_EMAIL_TO = os.environ.get("ALERT_EMAIL_TO", "")

# --- ntfy push (optional, instant push without Firebase) ---
NTFY_SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")

USER_AGENT = os.environ.get(
    "SCRAPER_USER_AGENT",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
)


def email_configured() -> bool:
    return bool(SMTP_HOST and ALERT_EMAIL_TO)


def ntfy_configured() -> bool:
    return bool(NTFY_TOPIC)
