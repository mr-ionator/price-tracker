from fastapi import APIRouter, Depends
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Notification
from ..schemas import NotificationOut

router = APIRouter(prefix="/notifications", tags=["notifications"])


@router.get("", response_model=list[NotificationOut])
def list_notifications(
    undelivered_only: bool = False,
    limit: int = 100,
    db: Session = Depends(get_db),
):
    query = select(Notification).order_by(Notification.created_at.desc(), Notification.id.desc())
    if undelivered_only:
        query = query.where(Notification.delivered.is_(False))
    return db.execute(query.limit(min(limit, 500))).scalars().all()


@router.post("/mark-delivered", status_code=204)
def mark_delivered(ids: list[int], db: Session = Depends(get_db)):
    if ids:
        db.execute(
            update(Notification).where(Notification.id.in_(ids)).values(delivered=True)
        )
        db.commit()
