package edu.campus.print.repo;

import edu.campus.print.common.ApiException;
import edu.campus.print.domain.ShopSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;

public interface ShopSettingsRepository extends JpaRepository<ShopSettings, Integer> {

    default ShopSettings current() {
        return findById(1).orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_SET_UP",
                "The shop is not set up yet. Run db/setup.sql in Supabase."));
    }
}
