package com.mamoji.finance.domain;

/** Finance-owned ledger membership projection. */
public class LedgerMember {
    public long id;
    public long companyId;
    public long ledgerId;
    public long userId;
    public String role;
    public String nickname;
    public String avatar;
    public String joinedAt;
}
