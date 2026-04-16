CREATE TABLE IF NOT EXISTS criminals (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    crime_type  VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    status      VARCHAR(50)  NOT NULL DEFAULT 'WANTED',
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS criminal_photos (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    criminal_id BIGINT       NOT NULL,
    photo_data  BLOB         NOT NULL,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (criminal_id) REFERENCES criminals(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS face_embeddings (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    criminal_id BIGINT       NOT NULL,
    photo_id    BIGINT,
    model_id    VARCHAR(64)  NOT NULL,
    embedding   BLOB         NOT NULL,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (criminal_id) REFERENCES criminals(id) ON DELETE CASCADE,
    FOREIGN KEY (photo_id) REFERENCES criminal_photos(id) ON DELETE SET NULL
);

ALTER TABLE face_embeddings ADD COLUMN IF NOT EXISTS model_id VARCHAR(64) NOT NULL DEFAULT 'legacy';

CREATE TABLE IF NOT EXISTS detection_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    criminal_id BIGINT,
    confidence  DOUBLE       NOT NULL,
    screenshot  BLOB,
    detected_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    notes       VARCHAR(1000),
    FOREIGN KEY (criminal_id) REFERENCES criminals(id) ON DELETE SET NULL
);
