package com.baokhang83.analysis;

import java.util.List;
import java.util.Map;

/**
 * Full result of a hexagonal architecture analysis across an entire project.
 */
public record HexagonalReport(

        /**
         * All discovered packages grouped by layer.
         * Keys present: DOMAIN, PORT, ADAPTER, UNKNOWN (absent if no packages in that layer).
         */
        Map<LayerType, List<PackageLayerInfo>> layers,

        /** Directed edges that violate the Dependency Rule. */
        List<BoundaryViolation> boundaryViolations,

        /**
         * Fraction of domain Ce edges that point away from infrastructure.
         * 1.0 = domain is completely isolated; 0.0 = all deps are infra/adapter.
         */
        double domainIsolationScore,

        /**
         * Average abstractness of all port packages.
         * 1.0 = every port package consists entirely of interfaces.
         */
        double portPurityScore,

        /**
         * Average concreteness (1 − A) of all adapter packages.
         * 1.0 = every adapter package is fully concrete.
         */
        double adapterConcretnessScore,

        /**
         * Composite score weighted as:
         * DIS × 0.40 + portPurity × 0.30 + violationFreeness × 0.30
         * Range [0, 1]; higher is better.
         */
        double overallHexagonalScore

) {}
