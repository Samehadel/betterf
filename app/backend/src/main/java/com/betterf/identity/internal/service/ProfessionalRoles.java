package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.IdentityViews.RoleView;

import java.util.List;

public final class ProfessionalRoles {
    private ProfessionalRoles() {}

    public static final List<RoleView> ALL =
            List.of(
                    new RoleView("FOUNDER_EXECUTIVE", "Founder / Executive"),
                    new RoleView("GENERAL_MANAGER", "General Manager"),
                    new RoleView("OPERATIONS_MANAGER", "Operations Manager"),
                    new RoleView("PRODUCT_MANAGER", "Product Manager"),
                    new RoleView("PROJECT_PROGRAM_MANAGER", "Project / Program Manager"),
                    new RoleView("ENGINEERING_MANAGER", "Engineering Manager"),
                    new RoleView("SOFTWARE_ENGINEER", "Software Engineer"),
                    new RoleView("QUALITY_ASSURANCE", "Quality Assurance / Test Engineer"),
                    new RoleView("DEVOPS_PLATFORM", "DevOps / Platform Engineer"),
                    new RoleView("DATA_ANALYST", "Data Analyst"),
                    new RoleView("DATA_SCIENTIST", "Data Scientist"),
                    new RoleView("UX_UI_DESIGNER", "UX / UI Designer"),
                    new RoleView("IT_SUPPORT", "IT Administrator / Support Specialist"),
                    new RoleView("INFORMATION_SECURITY", "Information Security Specialist"),
                    new RoleView("BUSINESS_ANALYST", "Business Analyst"),
                    new RoleView("HR_MANAGER", "HR Manager"),
                    new RoleView("HR_RECRUITMENT", "HR / Recruitment Specialist"),
                    new RoleView("MARKETING", "Marketing Specialist / Manager"),
                    new RoleView("SALES", "Sales Representative / Manager"),
                    new RoleView("CUSTOMER_SUCCESS", "Customer Success / Support Specialist"),
                    new RoleView("FINANCE_ACCOUNTING", "Finance / Accounting Specialist"),
                    new RoleView("LEGAL_COMPLIANCE", "Legal / Compliance Specialist"),
                    new RoleView(
                            "PROCUREMENT_SUPPLY_CHAIN", "Procurement / Supply Chain Specialist"),
                    new RoleView("OFFICE_COORDINATOR", "Administrative / Office Coordinator"),
                    new RoleView("OTHER", "Other"));
}
