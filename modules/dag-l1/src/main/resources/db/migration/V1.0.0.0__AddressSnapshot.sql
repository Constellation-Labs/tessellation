create table if not exists Address (
    address varchar(40) primary key,
    balance INTEGER,
    height INTEGER,
    hash varchar(64)
    constraint balance_non_negative check (balance >= 0)
    );
