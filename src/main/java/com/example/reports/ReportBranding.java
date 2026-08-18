package com.example.reports;

import java.io.Serializable;

/**
 * Company branding applied to generated PDF reports: name, contact details,
 * and a classpath resource pointing to the logo image.
 *
 * @param companyName
 *            company name shown in report headers
 * @param address
 *            postal address
 * @param phone
 *            phone number
 * @param email
 *            contact email
 * @param website
 *            website URL
 * @param logoResource
 *            absolute classpath resource path of the logo image (PNG)
 */
public record ReportBranding(String companyName, String address, String phone,
        String email, String website, String logoResource)
        implements Serializable {

    /** Branding used by the demo application. */
    public static final ReportBranding DEMO = new ReportBranding(
            "Acme Analytics Oy", "Ruukinkatu 2, 20540 Turku, Finland",
            "+358 40 123 4567", "reports@acme-analytics.example",
            "https://acme-analytics.example", "/reports/logo.png");
}
