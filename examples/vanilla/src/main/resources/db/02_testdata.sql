-- テストデータ投入
INSERT INTO orders (customer_id, status, created_at) VALUES
    ('CUST-001', 'CONFIRMED', '2024-06-01 10:00:00'),
    ('CUST-001', 'PENDING', '2024-06-15 14:30:00'),
    ('CUST-002', 'SHIPPED', '2024-06-10 09:00:00');

INSERT INTO order_lines (order_id, product_id, quantity, unit_price) VALUES
    (1, 'PROD-A', 2, 29.99),
    (1, 'PROD-B', 1, 49.99),
    (2, 'PROD-C', 3, 9.99),
    (3, 'PROD-A', 1, 29.99),
    (3, 'PROD-D', 5, 14.99);
