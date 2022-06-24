create table if not exists TrustInfo (
    peerId varbinary(512) not null primary key,
    score double,
    rating double,
    observationAdjustment double default 0,
    constraint score_normalized check (score >= -1 and score <= 1),
    constraint rating_normalized check (rating >= -1 and rating <= 1),
    constraint observationAdjustment_normalized check (observationAdjustment >= -1 and observationAdjustment <= 1)
);
