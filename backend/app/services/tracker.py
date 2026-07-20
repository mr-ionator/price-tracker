"""Price checking: scrape every tracked URL, record changes, fire alerts."""
import asyncio
import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import config
from ..database import SessionLocal
from ..models import Alert, PricePoint, Product, TrackedUrl, utcnow
from ..scrapers import ScrapeError, scraper_for_site
from . import notifier

logger = logging.getLogger(__name__)


def latest_price(db: Session, tracked_url_id: int) -> PricePoint | None:
    return db.execute(
        select(PricePoint)
        .where(PricePoint.tracked_url_id == tracked_url_id)
        .order_by(PricePoint.recorded_at.desc(), PricePoint.id.desc())
        .limit(1)
    ).scalar_one_or_none()


async def check_url(db: Session, tracked: TrackedUrl) -> PricePoint | None:
    """Scrape one URL; record a new PricePoint when the price changed.

    Returns the new PricePoint if a change (or first price) was recorded.
    """
    scraper = scraper_for_site(tracked.site)
    tracked.last_checked_at = utcnow()
    try:
        result = await scraper.scrape(tracked.url)
    except (ScrapeError, Exception) as exc:  # noqa: BLE001 - store any failure
        tracked.last_error = str(exc)[:500]
        db.commit()
        logger.warning("scrape failed for %s: %s", tracked.url, exc)
        return None

    tracked.last_error = None
    previous = latest_price(db, tracked.id)
    if previous is not None and abs(previous.price - result.price) < 0.005:
        db.commit()
        return None

    point = PricePoint(
        tracked_url_id=tracked.id, price=result.price, currency=result.currency
    )
    db.add(point)
    db.commit()

    product = db.get(Product, tracked.product_id)
    if previous is not None and product is not None:
        direction = "dropped" if result.price < previous.price else "increased"
        notifier.notify(
            db,
            title=f"{product.name}: price {direction}",
            body=(
                f"{product.name} on {tracked.site} {direction} from "
                f"€{previous.price:.2f} to €{result.price:.2f}\n{tracked.url}"
            ),
            kind="price_change",
            product_id=product.id,
        )
    return point


def evaluate_alerts(db: Session, product: Product) -> None:
    """Fire alerts whose target price is met by any of the product's URLs."""
    current: list[tuple[TrackedUrl, PricePoint]] = []
    for tracked in product.urls:
        point = latest_price(db, tracked.id)
        if point is not None:
            current.append((tracked, point))
    if not current:
        return
    best_url, best = min(current, key=lambda pair: pair[1].price)

    for alert in product.alerts:
        if not alert.active:
            continue
        if best.price <= alert.target_price:
            if not alert.triggered:
                alert.triggered = True
                db.commit()
                notifier.notify(
                    db,
                    title=f"{product.name} hit your target price!",
                    body=(
                        f"{product.name} is now €{best.price:.2f} on {best_url.site} "
                        f"(target: €{alert.target_price:.2f})\n{best_url.url}"
                    ),
                    kind="alert",
                    product_id=product.id,
                )
        elif alert.triggered:
            alert.triggered = False
            db.commit()


async def check_product(db: Session, product: Product) -> None:
    for index, tracked in enumerate(product.urls):
        if index > 0:
            await asyncio.sleep(config.SCRAPE_DELAY_SECONDS)
        await check_url(db, tracked)
    evaluate_alerts(db, product)


async def check_all_products() -> None:
    db = SessionLocal()
    try:
        products = db.execute(select(Product)).scalars().all()
        for index, product in enumerate(products):
            if index > 0:
                await asyncio.sleep(config.SCRAPE_DELAY_SECONDS)
            await check_product(db, product)
    finally:
        db.close()


async def scheduler_loop() -> None:
    interval = max(config.CHECK_INTERVAL_MINUTES, 1) * 60
    logger.info("price check scheduler started (every %s min)", interval // 60)
    while True:
        try:
            await check_all_products()
        except Exception:
            logger.exception("scheduled price check failed")
        await asyncio.sleep(interval)
