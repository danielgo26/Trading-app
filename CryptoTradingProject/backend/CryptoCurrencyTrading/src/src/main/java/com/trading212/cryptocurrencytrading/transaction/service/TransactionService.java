package com.trading212.cryptocurrencytrading.transaction.service;

import com.trading212.cryptocurrencytrading.crypto.service.CryptoService;
import com.trading212.cryptocurrencytrading.crypto.model.CryptoCurrency;
import com.trading212.cryptocurrencytrading.exception.IllegalTransactionStateException;
import com.trading212.cryptocurrencytrading.exception.InsufficientQuantityException;
import com.trading212.cryptocurrencytrading.exception.NotExistingCryptoException;
import com.trading212.cryptocurrencytrading.exception.NotExistingTraderException;
import com.trading212.cryptocurrencytrading.wallet.model.CryptoWalletEntity;
import com.trading212.cryptocurrencytrading.trader.model.Trader;
import com.trading212.cryptocurrencytrading.transaction.model.Transaction;
import com.trading212.cryptocurrencytrading.transaction.model.TransactionType;
import com.trading212.cryptocurrencytrading.wallet.service.CryptoWalletEntityService;
import com.trading212.cryptocurrencytrading.api.kraken.service.KrakenWebSocketService;
import com.trading212.cryptocurrencytrading.trader.service.TraderService;
import com.trading212.cryptocurrencytrading.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final TraderService traderService;
    private final CryptoService cryptoService;
    private final CryptoWalletEntityService cryptoWalletEntityService;
    private final KrakenWebSocketService krakenWebSocketService;

    public TransactionService(TransactionRepository transactionRepository,
                              TraderService traderService,
                              CryptoService cryptoService,
                              CryptoWalletEntityService cryptoWalletEntityService,
                              KrakenWebSocketService krakenWebSocketService) {
        this.transactionRepository = transactionRepository;
        this.traderService = traderService;
        this.cryptoService = cryptoService;
        this.cryptoWalletEntityService = cryptoWalletEntityService;
        this.krakenWebSocketService = krakenWebSocketService;
    }

    public List<Transaction> getAllTransactions(Long traderId) {
        return transactionRepository.getAllTransactionsByTraderId(traderId);
    }

    public Transaction createTransaction(Transaction transaction) {
        Trader trader = traderService.getTraderById(transaction.getTraderId());
        BigDecimal totalCryptoCost = transaction.getCryptoCurrencyTradedPrice().multiply(transaction.getAmount());

        validateTransactionState(transaction, trader);

        if (transaction.getTransactionType() == TransactionType.BUY) {
            handleBuyTransaction(transaction, trader, totalCryptoCost);
        } else if (transaction.getTransactionType() == TransactionType.SELL) {
            handleSellTransaction(transaction, trader, totalCryptoCost);
        }

        return finalizeTransaction(transaction, trader);
    }

    private void validateTransactionState(Transaction transaction, Trader trader) {
        if (trader == null) {
            throw new NotExistingTraderException("The trader that executed the transaction cannot be found");
        }

        if (transaction.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalTransactionStateException("Transaction amount must be greater than zero");
        }

        if (transaction.getCryptoCurrencyTradedPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalTransactionStateException("Transaction trading price must be greater than zero");
        }

        Map<String, CryptoCurrency> cryptoCurrenciesMap = cryptoService.getCryptoCurrenciesMap();
        String cryptoSymbol = transaction.getCryptoCurrencySymbol();

        if (!cryptoCurrenciesMap.containsKey(cryptoSymbol)) {
            throw new NotExistingCryptoException("Crypto: " + cryptoSymbol + " cannot be found in the system");
        }
    }

    private void handleBuyTransaction(Transaction transaction, Trader trader, BigDecimal totalCryptoCost) {
        if (trader.getBalance().compareTo(totalCryptoCost) < 0) {
            throw new InsufficientQuantityException("Insufficient trader balance");
        }

        trader.setBalance(trader.getBalance().subtract(totalCryptoCost));
        BigDecimal newAmount = transaction.getAmount();
        CryptoWalletEntity cryptoWalletEntity = new CryptoWalletEntity(null, trader.getId(),
            transaction.getCryptoCurrencyName(), transaction.getCryptoCurrencySymbol(),
            newAmount, LocalDateTime.now());

        if (!trader.getCryptoWallet().containsKey(transaction.getCryptoCurrencyName())) {
            cryptoWalletEntityService.createCryptoWalletEntity(cryptoWalletEntity);
        } else {
            newAmount =
                newAmount.add(trader.getCryptoWallet().get(transaction.getCryptoCurrencyName()).getAmount());
            cryptoWalletEntity.setAmount(newAmount);

            Long cryptoWalletEntityId = cryptoWalletEntityService.getCryptoWalletEntityId(cryptoWalletEntity);
            cryptoWalletEntity.setCryptoWalletEntityId(cryptoWalletEntityId);

            cryptoWalletEntityService.updateCryptoWalletEntity(cryptoWalletEntity);
        }

        trader.getCryptoWallet().put(transaction.getCryptoCurrencyName(), cryptoWalletEntity);
    }

    private void handleSellTransaction(Transaction transaction, Trader trader, BigDecimal totalCryptoCost) {
        if (!trader.getCryptoWallet().containsKey(transaction.getCryptoCurrencyName())) {
            throw new NotExistingCryptoException("Not existing crypto in wallet");
        }
        if (trader.getCryptoWallet()
            .get(transaction.getCryptoCurrencyName()).getAmount().compareTo(transaction.getAmount()) < 0) {
            throw new InsufficientQuantityException("Insufficient crypto amount");
        }

        trader.setBalance(trader.getBalance().add(totalCryptoCost));
        BigDecimal  newAmount = trader.getCryptoWallet().get(transaction.getCryptoCurrencyName()).getAmount()
            .subtract(transaction.getAmount());
        CryptoWalletEntity cryptoWalletEntity = new CryptoWalletEntity(null, trader.getId(),
            transaction.getCryptoCurrencyName(), transaction.getCryptoCurrencySymbol(),
            transaction.getAmount(), LocalDateTime.now());
        Long cryptoWalletEntityId = cryptoWalletEntityService.getCryptoWalletEntityId(cryptoWalletEntity);
        cryptoWalletEntity.setCryptoWalletEntityId(cryptoWalletEntityId);

        if (newAmount.compareTo(new BigDecimal("0.01")) < 0) {
            trader.getCryptoWallet().remove(transaction.getCryptoCurrencyName());
            cryptoWalletEntityService.deleteCryptoWalletEntity(cryptoWalletEntity);
        } else {
            cryptoWalletEntity.setAmount(newAmount);

            trader.getCryptoWallet().put(transaction.getCryptoCurrencyName(), cryptoWalletEntity);

            cryptoWalletEntityService.updateCryptoWalletEntity(cryptoWalletEntity);
        }
    }

    private Transaction finalizeTransaction(Transaction transaction, Trader trader) {
        trader.getTransactions().add(transaction);
        traderService.updateTrader(trader.getId(), trader);

        transaction.setProfit(calculateTransactionProfit(transaction, trader));

        return transactionRepository.saveTransaction(transaction);
    }

    private BigDecimal calculateTransactionProfit(Transaction transaction, Trader trader) {
        BigDecimal buyingExpenses = transaction.getCryptoCurrencyTradedPrice(); // same as "ask" in time of buying
        BigDecimal highestPriceToSell =
            krakenWebSocketService.getTickerData().get(transaction.getCryptoCurrencySymbol()).getBid();

        if (transaction.getTransactionType() == TransactionType.SELL) {
            BigDecimal avgBuyingExpenses =
                getAvgPriceOfCryptoInWallet(trader, transaction.getCryptoCurrencySymbol());

            return  highestPriceToSell.subtract(avgBuyingExpenses).multiply(transaction.getAmount());
        }

        return highestPriceToSell.subtract(buyingExpenses).multiply(transaction.getAmount());
    }

    private BigDecimal getAvgPriceOfCryptoInWallet(Trader trader, String cryptoSymbol) {
        List<Transaction> filtered = trader.getTransactions().stream()
            .filter(tr -> tr.getTransactionType() == TransactionType.BUY
            && Objects.equals(tr.getCryptoCurrencySymbol(), cryptoSymbol))
            .toList();

        return filtered.stream()
            .map(Transaction::getCryptoCurrencyTradedPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add).divide(new BigDecimal(filtered.size()), RoundingMode.HALF_UP);
    }

}