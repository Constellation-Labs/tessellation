create table if not exists Address (
    address varchar(40) primary key,
    balance bigint default 0
    constraint balance_non_negative check (balance >= 0)
);
