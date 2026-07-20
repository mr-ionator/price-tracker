from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Alert, PricePoint, Product, TrackedUrl
from ..scrapers import site_for_url
from ..schemas import (
    AlertCreate,
    AlertOut,
    PricePointOut,
    ProductCreate,
    ProductDetailOut,
    ProductOut,
    TrackedUrlOut,
)
from ..services import tracker

router = APIRouter(prefix="/products", tags=["products"])


def _url_out(db: Session, tracked: TrackedUrl) -> TrackedUrlOut:
    out = TrackedUrlOut.model_validate(tracked)
    point = tracker.latest_price(db, tracked.id)
    if point is not None:
        out.latest_price = PricePointOut.model_validate(point)
    return out


def _product_out(db: Session, product: Product) -> ProductOut:
    return ProductOut(
        id=product.id,
        name=product.name,
        created_at=product.created_at,
        urls=[_url_out(db, u) for u in product.urls],
        alerts=[AlertOut.model_validate(a) for a in product.alerts],
    )


def _get_product(db: Session, product_id: int) -> Product:
    product = db.get(Product, product_id)
    if product is None:
        raise HTTPException(status_code=404, detail="product not found")
    return product


@router.get("", response_model=list[ProductOut])
def list_products(db: Session = Depends(get_db)):
    products = db.execute(select(Product).order_by(Product.id)).scalars().all()
    return [_product_out(db, p) for p in products]


@router.post("", response_model=ProductOut, status_code=201)
async def create_product(payload: ProductCreate, db: Session = Depends(get_db)):
    product = Product(name=payload.name)
    for item in payload.urls:
        product.urls.append(TrackedUrl(url=item.url, site=site_for_url(item.url)))
    db.add(product)
    db.commit()
    # Fetch initial prices right away so the app shows data immediately.
    await tracker.check_product(db, product)
    return _product_out(db, product)


@router.get("/{product_id}", response_model=ProductDetailOut)
def get_product(product_id: int, db: Session = Depends(get_db)):
    product = _get_product(db, product_id)
    history: dict[int, list[PricePointOut]] = {}
    for tracked in product.urls:
        points = (
            db.execute(
                select(PricePoint)
                .where(PricePoint.tracked_url_id == tracked.id)
                .order_by(PricePoint.recorded_at)
            )
            .scalars()
            .all()
        )
        history[tracked.id] = [PricePointOut.model_validate(p) for p in points]
    base = _product_out(db, product)
    return ProductDetailOut(**base.model_dump(), history=history)


@router.delete("/{product_id}", status_code=204)
def delete_product(product_id: int, db: Session = Depends(get_db)):
    db.delete(_get_product(db, product_id))
    db.commit()


@router.post("/{product_id}/refresh", response_model=ProductOut)
async def refresh_product(product_id: int, db: Session = Depends(get_db)):
    product = _get_product(db, product_id)
    await tracker.check_product(db, product)
    return _product_out(db, product)


@router.post("/{product_id}/alerts", response_model=AlertOut, status_code=201)
def create_alert(product_id: int, payload: AlertCreate, db: Session = Depends(get_db)):
    product = _get_product(db, product_id)
    alert = Alert(product_id=product.id, target_price=payload.target_price)
    db.add(alert)
    db.commit()
    tracker.evaluate_alerts(db, product)
    return AlertOut.model_validate(alert)
