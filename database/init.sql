drop table VerificationRequest;
drop table AccountTimeout;

create table VerificationRequest(
    discordName varchar(120) primary key,
    roleId char(12),
    code char(60)
);
create table AccountTimeout(
    accountName varchar(120) primary key,
    suspendedFrom datetime,
    suspendedCount int
);
