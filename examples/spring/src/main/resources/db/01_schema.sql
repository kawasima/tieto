-- DDL: テーブル定義（人間が管理する部分）

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE order_lines (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  VARCHAR(50) NOT NULL,
    quantity    INT NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_order_lines_order_id ON order_lines(order_id);
