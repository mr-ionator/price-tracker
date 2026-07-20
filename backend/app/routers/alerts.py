from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Alert
from ..schemas import AlertOut

router = APIRouter(prefix="/alerts", tags=["alerts"])


def _get_alert(db: Session, alert_id: int) -> Alert:
    alert = db.get(Alert, alert_id)
    if alert is None:
        raise HTTPException(status_code=404, detail="alert not found")
    return alert


@router.delete("/{alert_id}", status_code=204)
def delete_alert(alert_id: int, db: Session = Depends(get_db)):
    db.delete(_get_alert(db, alert_id))
    db.commit()


@router.post("/{alert_id}/toggle", response_model=AlertOut)
def toggle_alert(alert_id: int, db: Session = Depends(get_db)):
    alert = _get_alert(db, alert_id)
    alert.active = not alert.active
    if not alert.active:
        alert.triggered = False
    db.commit()
    return AlertOut.model_validate(alert)
