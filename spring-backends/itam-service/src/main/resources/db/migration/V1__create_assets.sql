CREATE TABLE assets (
    id UUID PRIMARY KEY,
    tag VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    serial_number VARCHAR(255),
    assigned_to VARCHAR(255),
    purchase_value DECIMAL(19,2),
    purchase_date DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_asset_tag UNIQUE(tag)
);
CREATE INDEX idx_asset_type_status ON assets(type, status);
