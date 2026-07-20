from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class TrackedUrlCreate(BaseModel):
    url: str = Field(min_length=8)


class ProductCreate(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    urls: list[TrackedUrlCreate] = Field(min_length=1)


class PricePointOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    price: float
    currency: str
    recorded_at: datetime


class TrackedUrlOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    site: str
    url: str
    last_checked_at: datetime | None
    last_error: str | None
    latest_price: PricePointOut | None = None


class AlertCreate(BaseModel):
    target_price: float = Field(gt=0)


class AlertOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    product_id: int
    target_price: float
    active: bool
    triggered: bool


class ProductOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    created_at: datetime
    urls: list[TrackedUrlOut]
    alerts: list[AlertOut]


class ProductDetailOut(ProductOut):
    history: dict[int, list[PricePointOut]]  # tracked_url_id -> points


class NotificationOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    title: str
    body: str
    kind: str
    product_id: int | None
    created_at: datetime
    delivered: bool
