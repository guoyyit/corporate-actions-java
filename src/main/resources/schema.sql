CREATE TABLE IF NOT EXISTS corporate_event (
    event_id       VARCHAR(64)  NOT NULL PRIMARY KEY,
    symbol         VARCHAR(64)  NOT NULL,
    effective_date DATE         NOT NULL,
    event_type     VARCHAR(16)  NOT NULL,
    params_json    VARCHAR(2048) NOT NULL
);

CREATE TABLE IF NOT EXISTS raw_price (
    symbol VARCHAR(64) NOT NULL,
    trade_date DATE   NOT NULL,
    close  DECIMAL(20, 8) NOT NULL,
    PRIMARY KEY (symbol, trade_date)
);
