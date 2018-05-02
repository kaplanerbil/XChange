package org.knowm.xchange.exmo.service;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.marketdata.Trades;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.dto.trade.UserTrade;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exmo.ExmoExchange;
import org.knowm.xchange.exmo.dto.trade.ExmoUserTrades;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.CancelOrderByIdParams;
import org.knowm.xchange.service.trade.params.CancelOrderParams;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.TradeHistoryParamLimit;
import org.knowm.xchange.service.trade.params.TradeHistoryParamOffset;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParams;
import org.knowm.xchange.service.trade.params.orders.OrderQueryParams;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class ExmoTradeService extends ExmoTradeServiceRaw implements TradeService {
    public ExmoTradeService(ExmoExchange exmoExchange) {
        super(exmoExchange);
    }

    @Override
    public OpenOrders getOpenOrders() throws IOException {
        return getOpenOrders(null);
    }

    @Override
    public OpenOrders getOpenOrders(OpenOrdersParams params) throws IOException {
        return new OpenOrders(openOrders());
    }

    @Override
    public String placeLimitOrder(LimitOrder limitOrder) throws IOException {
        String type = limitOrder.getType().equals(Order.OrderType.BID) ? "buy" : "sell";

        Map map = exmo.orderCreate(
                signatureCreator, apiKey, exchange.getNonceFactory(),
                format(limitOrder.getCurrencyPair()),
                limitOrder.getOriginalAmount(),
                limitOrder.getLimitPrice(),
                type
        );

        Boolean result = (Boolean) map.get("result");
        if (!result)
            throw new ExchangeException("Failed to place order: " + map.get("error"));

        return map.get("order_id").toString();
    }

    @Override
    public boolean cancelOrder(CancelOrderParams orderParams) throws IOException {
        if (orderParams instanceof CancelOrderByIdParams) {
            CancelOrderByIdParams params = (CancelOrderByIdParams) orderParams;
            String orderId = params.getOrderId();
            Map map = exmo.orderCancel(signatureCreator, apiKey, exchange.getNonceFactory(), orderId);
            return (boolean) map.get("result");
        }

        return false;
    }

    @Override
    public UserTrades getTradeHistory(TradeHistoryParams params) throws IOException {
        Integer limit = 10000;
        Long offset = 0L;
        List<CurrencyPair> currencyPairs = new ArrayList<>();

        if (params instanceof ExmoTradeHistoryParams) {
            ExmoTradeHistoryParams exmoTradeHistoryParams = (ExmoTradeHistoryParams) params;
            currencyPairs.addAll(exmoTradeHistoryParams.currencyPairs);
        } else if (params instanceof TradeHistoryParamCurrencyPair) {
            TradeHistoryParamCurrencyPair tradeHistoryParamCurrencyPair = (TradeHistoryParamCurrencyPair) params;
            currencyPairs.add(tradeHistoryParamCurrencyPair.getCurrencyPair());
        }

        if (params instanceof TradeHistoryParamLimit) {
            limit = ((TradeHistoryParamLimit) params).getLimit();
        }

        if (params instanceof TradeHistoryParamOffset) {
            offset = ((TradeHistoryParamOffset) params).getOffset();
        }

        List<UserTrade> trades = trades(limit, offset, currencyPairs);

        return new UserTrades(trades, Trades.TradeSortType.SortByTimestamp);
    }

    public static class ExmoTradeHistoryParams implements TradeHistoryParamLimit, TradeHistoryParamOffset {
        private Integer limit = 1000;
        private Long offset = 0L;
        private Collection<CurrencyPair> currencyPairs = new ArrayList<>();

        public ExmoTradeHistoryParams() {
        }

        public ExmoTradeHistoryParams(Integer limit, Long offset, Collection<CurrencyPair> currencyPairs) {
            this.limit = limit;
            this.offset = offset;
            this.currencyPairs = currencyPairs;
        }

        public Collection<CurrencyPair> getCurrencyPairs() {
            return currencyPairs;
        }

        public void setCurrencyPairs(Collection<CurrencyPair> currencyPairs) {
            this.currencyPairs = currencyPairs;
        }

        @Override
        public Integer getLimit() {
            return limit;
        }

        @Override
        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        @Override
        public Long getOffset() {
            return offset;
        }

        @Override
        public void setOffset(Long offset) {
            this.offset = offset;
        }
    }

    @Override
    public String placeStopOrder(StopOrder stopOrder) throws IOException {
        throw new NotAvailableFromExchangeException();
    }

    @Override
    public String placeMarketOrder(MarketOrder marketOrder) throws IOException {
        throw new NotAvailableFromExchangeException();//limit orders only
    }

    @Override
    /**
     * Warning: this make multiple api requests (one per parameter) so may result in you exceeding your api limits
     */
    public Collection<Order> getOrder(OrderQueryParams... orderQueryParams) throws IOException {
        List<Order> results = new ArrayList<>();

        for (OrderQueryParams orderQueryParam : orderQueryParams) {
            String orderId = orderQueryParam.getOrderId();

            Order.OrderType type = null;
            CurrencyPair currencyPair = null;
            String id = null;
            Date timestamp = null;
            BigDecimal totalValue = BigDecimal.ZERO;
            BigDecimal cumulativeAmount = BigDecimal.ZERO;
            BigDecimal fee = BigDecimal.ZERO;
            Order.OrderStatus status = Order.OrderStatus.UNKNOWN;

            ExmoUserTrades exmoUserTrades = userTrades(orderId);
            if (exmoUserTrades == null)
                continue;

            BigDecimal originalAmount = exmoUserTrades.getOriginalAmount();

            for (UserTrade userTrade : exmoUserTrades.getUserTrades()) {
                type = userTrade.getType();
                currencyPair = userTrade.getCurrencyPair();
                id = userTrade.getOrderId();

                if (timestamp == null)
                    timestamp = userTrade.getTimestamp();

                if (timestamp.getTime() < userTrade.getTimestamp().getTime())//arbitarily use the timestmap from the most recent trade
                    timestamp = userTrade.getTimestamp();

                BigDecimal amountForFill = userTrade.getOriginalAmount();
                BigDecimal priceForFill = userTrade.getPrice();
                BigDecimal value = amountForFill.multiply(priceForFill);

                cumulativeAmount = cumulativeAmount.add(amountForFill);
                totalValue = totalValue.add(value);
            }

            BigDecimal averagePrice = totalValue.divide(cumulativeAmount, 8, RoundingMode.HALF_UP);

            Order order = new MarketOrder(
                    type,
                    originalAmount,
                    currencyPair,
                    id,
                    timestamp,
                    averagePrice,
                    cumulativeAmount,
                    fee,
                    status
            );

            results.add(order);
        }

        return results;
    }
}
