import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import config
from .database import Base, engine
from .routers import alerts, notifications, products
from .services.tracker import scheduler_loop

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    task = asyncio.create_task(scheduler_loop())
    yield
    task.cancel()


app = FastAPI(title="Price Tracker", version="1.0.0", lifespan=lifespan)
app.include_router(products.router)
app.include_router(alerts.router)
app.include_router(notifications.router)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "check_interval_minutes": config.CHECK_INTERVAL_MINUTES,
        "email_configured": config.email_configured(),
        "ntfy_configured": config.ntfy_configured(),
    }
