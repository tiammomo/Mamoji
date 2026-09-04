package com.mamoji.people.domain;

/** Work, project, education, or training history attached to an employee. */
public class EmployeeExperience {
    public long id;
    public long employeeId;
    public String type;
    public String organization;
    public String title;
    public String startDate;
    public String endDate;
    public String description;
    public String achievements;
    public String skills;
    public String createdAt;
    public String updatedAt;
}
