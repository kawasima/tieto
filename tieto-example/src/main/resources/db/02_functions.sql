-- PostgreSQL Functions: Repositoryの実装
-- 本番ではtieto-generatorがAIで生成する部分。ここでは手書き。

-- OrderRepository.findById(Long id) -> Optional<Order>
CREATE OR REPLACE FUNCTION order_repository_find_by_id(p_id BIGINT)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    result JSONB;
BEGIN
    SELECT jsonb_build_object(
        'id', o.id,
        'customerId', o.customer_id,
        'status', o.status,
        'createdAt', o.created_at,
        'lines', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'productId', ol.product_id,
                'quantity', ol.quantity,
                'unitPrice', ol.unit_price
            ) ORDER BY ol.id)
            FROM order_lines ol WHERE ol.order_id = o.id
        ), '[]'::jsonb)
    ) INTO result
    FROM orders o
    WHERE o.id = p_id;

    RETURN result;
END;
$$;

-- OrderRepository.findByCustomerId(String customerId) -> List<Order>
CREATE OR REPLACE FUNCTION order_repository_find_by_customer_id(p_customer_id TEXT)
RETURNS SETOF JSONB
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT jsonb_build_object(
        'id', o.id,
        'customerId', o.customer_id,
        'status', o.status,
        'createdAt', o.created_at,
        'lines', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'productId', ol.product_id,
                'quantity', ol.quantity,
                'unitPrice', ol.unit_price
            ) ORDER BY ol.id)
            FROM order_lines ol WHERE ol.order_id = o.id
        ), '[]'::jsonb)
    )
    FROM orders o
    WHERE o.customer_id = p_customer_id
    ORDER BY o.created_at DESC;
END;
$$;

-- OrderRepository.save(Order order) -> void
CREATE OR REPLACE FUNCTION order_repository_save(p_order JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    new_order_id BIGINT;
    line JSONB;
BEGIN
    INSERT INTO orders (customer_id, status, created_at)
    VALUES (
        p_order->>'customerId',
        COALESCE(p_order->>'status', 'PENDING'),
        COALESCE((p_order->>'createdAt')::timestamp, NOW())
    )
    RETURNING id INTO new_order_id;

    IF p_order->'lines' IS NOT NULL AND jsonb_array_length(p_order->'lines') > 0 THEN
        FOR line IN SELECT * FROM jsonb_array_elements(p_order->'lines')
        LOOP
            INSERT INTO order_lines (order_id, product_id, quantity, unit_price)
            VALUES (
                new_order_id,
                line->>'productId',
                (line->>'quantity')::int,
                (line->>'unitPrice')::numeric
            );
        END LOOP;
    END IF;
END;
$$;

-- OrderRepository.updateStatus(Long id, OrderStatus status) -> void
CREATE OR REPLACE FUNCTION order_repository_update_status(p_id BIGINT, p_status TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE orders SET status = p_status WHERE id = p_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Order not found: %', p_id
            USING ERRCODE = 'P0002';
    END IF;
END;
$$;

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
