CREATE TABLE games (
                       id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       table_name   VARCHAR(30) NOT NULL,
                       started_at   TIMESTAMPTZ NOT NULL,
                       ended_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                       hands_played INT         NOT NULL CHECK (hands_played >= 0)
);

CREATE TABLE game_players (
                              id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                              game_id      BIGINT NOT NULL REFERENCES games (id) ON DELETE CASCADE,
                              user_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                              position     INT    NOT NULL CHECK (position >= 1),
                              hands_played INT    NOT NULL CHECK (hands_played >= 0),
                              hands_won    INT    NOT NULL CHECK (hands_won >= 0),
                              UNIQUE (game_id, user_id)
);

CREATE INDEX game_players_user_id_idx ON game_players (user_id);