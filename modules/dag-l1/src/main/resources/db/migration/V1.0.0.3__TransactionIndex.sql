create table if not exists TransactionIndex (
    hash varchar(64) primary key,
    sourceAddress varchar(40) not null,
    destinationAddress varchar(40) not null,
    height long not null default 0,
    networkStatus varchar(40) not null,
    transactionBytes blob
);
