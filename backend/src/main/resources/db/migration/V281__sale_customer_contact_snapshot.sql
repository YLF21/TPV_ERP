ALTER TABLE documento ADD COLUMN cliente_contacto_snapshot jsonb;
ALTER TABLE payment_terminal_operation ADD COLUMN sale_customer_contact_snapshot jsonb;
