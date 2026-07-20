"""Fan-out of a single event to every configured channel.

Channels:
  - inbox: a Notification row the Android app polls and raises as a system
    notification (works with zero external configuration).
  - email: SMTP, if SMTP_HOST + ALERT_EMAIL_TO are set.
  - ntfy: instant push to a ntfy topic, if NTFY_TOPIC is set.
"""
import logging
import smtplib
from email.message import EmailMessage

import httpx
from sqlalchemy.orm import Session

from .. import config
from ..models import Notification

logger = logging.getLogger(__name__)


def _send_email(title: str, body: str) -> None:
    message = EmailMessage()
    message["Subject"] = f"[Price Tracker] {title}"
    message["From"] = config.SMTP_FROM
    message["To"] = config.ALERT_EMAIL_TO
    message.set_content(body)

    with smtplib.SMTP(config.SMTP_HOST, config.SMTP_PORT, timeout=30) as smtp:
        if config.SMTP_STARTTLS:
            smtp.starttls()
        if config.SMTP_USER:
            smtp.login(config.SMTP_USER, config.SMTP_PASSWORD)
        smtp.send_message(message)


def _send_ntfy(title: str, body: str) -> None:
    url = f"{config.NTFY_SERVER.rstrip('/')}/{config.NTFY_TOPIC}"
    httpx.post(
        url,
        content=body.encode(),
        headers={"Title": title, "Tags": "money_with_wings"},
        timeout=15,
    ).raise_for_status()


def notify(
    db: Session,
    *,
    title: str,
    body: str,
    kind: str,
    product_id: int | None = None,
) -> Notification:
    notification = Notification(title=title, body=body, kind=kind, product_id=product_id)
    db.add(notification)
    db.commit()

    if config.email_configured():
        try:
            _send_email(title, body)
        except Exception:
            logger.exception("email notification failed")
    if config.ntfy_configured():
        try:
            _send_ntfy(title, body)
        except Exception:
            logger.exception("ntfy notification failed")
    return notification
