package com.github.guoyyit.corporateactionsjava.core.repo;

import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.List;

@Repository
public class RawPriceRepository {

    private final JdbcTemplate jdbcTemplate;

    public RawPriceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RawPrice> findBySymbol(String symbol) {
        return jdbcTemplate.query(
                "SELECT symbol, trade_date, close FROM raw_price WHERE symbol = ? ORDER BY trade_date",
                (rs, rowNum) -> new RawPrice(
                        rs.getString("symbol"),
                        rs.getDate("trade_date").toLocalDate(),
                        rs.getBigDecimal("close")),
                symbol);
    }

    public void upsert(RawPrice price) {
        jdbcTemplate.update(
                "MERGE INTO raw_price(symbol, trade_date, close) KEY(symbol, trade_date) VALUES (?, ?, ?)",
                price.symbol(),
                Date.valueOf(price.date()),
                price.close().setScale(8, RoundingMode.HALF_UP));
    }

    public List<String> findAllSymbols() {
        return jdbcTemplate.queryForList("SELECT DISTINCT symbol FROM raw_price", String.class);
    }

    public void deleteBySymbol(String symbol) {
        jdbcTemplate.update("DELETE FROM raw_price WHERE symbol = ?", symbol);
    }
}
