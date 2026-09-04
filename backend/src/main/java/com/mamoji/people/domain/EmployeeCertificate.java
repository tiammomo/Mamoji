package com.mamoji.people.domain;

/** Professional credential attached to an employee profile. */
public class EmployeeCertificate {
    public long id;
    public long employeeId;
    public String name;
    public String category;
    public String level;
    public String issuer;
    public String certificateNo;
    public String issueDate;
    public String expiryDate;
    public String verificationStatus;
    public String materialStatus;
    public String note;
    public String createdAt;
    public String updatedAt;
}
