package com.voltx.evgenee.service.impl;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import com.voltx.evgenee.dto.requests.PaymentRequestDto;
import com.voltx.evgenee.dto.responses.PaymentResponseDto;
import com.voltx.evgenee.entity.Booking;
import com.voltx.evgenee.entity.Payment;
import com.voltx.evgenee.enums.BookingStatus;
import com.voltx.evgenee.enums.PaymentMethod;
import com.voltx.evgenee.enums.PaymentStatus;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.BookingRepository;
import com.voltx.evgenee.repository.PaymentRepository;
import com.voltx.evgenee.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final ObjectProvider<RazorpayClient> razorpayClientProvider;

    @Value("${razorpay.key.secret:}")
    private String razorpayKeySecret;

    @Value("${razorpay.key.id:}")
    private String razorpayKeyId;

    @Override
    @Transactional
    public PaymentResponseDto createOrder(PaymentRequestDto requestDto) {
        Booking booking = null;
        if (requestDto.getBookingId() != null) {
            booking = bookingRepository.findById(requestDto.getBookingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + requestDto.getBookingId()));
        }

        BigDecimal rupeeAmount = requestDto.getAmount() != null ? requestDto.getAmount() : BigDecimal.ZERO;
        int amountInPaise = toPaise(rupeeAmount);
        if (amountInPaise < 10) {
            throw new BadRequestException("Payment amount must be at least 10 paise");
        }

        String currency = requestDto.getCurrency() != null ? requestDto.getCurrency().toUpperCase(Locale.ROOT) : "INR";
        String receipt = buildReceipt(booking);
        Order order = createRazorpayOrder(amountInPaise, currency, receipt, booking);

        String orderId = order.get("id");
        Integer razorpayAmount = order.get("amount");
        String razorpayCurrency = order.get("currency");
        String razorpayReceipt = order.get("receipt");
        String razorpayStatus = order.get("status");

        Payment payment = Payment.builder()
                .booking(booking)
                .amount(fromPaise(razorpayAmount != null ? razorpayAmount : amountInPaise))
                .currency(razorpayCurrency != null ? razorpayCurrency : currency)
                .orderId(orderId)
                .receipt(razorpayReceipt != null ? razorpayReceipt : receipt)
                .razorpayStatus(razorpayStatus != null ? razorpayStatus : "created")
                .transactionId(requestDto.getTransactionId())
                .method(parseMethod(requestDto.getMethod()))
                .status(PaymentStatus.PENDING)
                .build();

        return toOrderResponse(paymentRepository.save(payment), razorpayAmount != null ? razorpayAmount : amountInPaise);
    }

    @Override
    @Transactional
    public PaymentResponseDto updatePayment(PaymentRequestDto requestDto) {
        String orderId = requestDto.getOrderId();
        String paymentId = requestDto.getPaymentId() != null ? requestDto.getPaymentId() : requestDto.getTransactionId();
        if (orderId == null || orderId.isBlank()) {
            throw new BadRequestException("Razorpay order id is required");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new BadRequestException("Razorpay payment id is required");
        }

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found: " + orderId));

        boolean requestedPaid = "paid".equalsIgnoreCase(requestDto.getStatus())
                || "captured".equalsIgnoreCase(requestDto.getStatus())
                || "authorized".equalsIgnoreCase(requestDto.getStatus());
        if (!requestedPaid) {
            payment.setTransactionId(paymentId);
            payment.setStatus(PaymentStatus.FAILED);
            return toResponse(paymentRepository.save(payment));
        }

        boolean verified = verifyPayment(requestDto, payment, paymentId);
        payment.setTransactionId(paymentId);
        payment.setRazorpaySignature(signatureFrom(requestDto));
        payment.setStatus(verified ? PaymentStatus.PAID : PaymentStatus.FAILED);
        payment.setPaidAt(verified ? Instant.now() : null);

        Payment saved = paymentRepository.save(payment);
        if (!verified) {
            throw new BadRequestException("Razorpay payment verification failed");
        }

        confirmLinkedAdvanceBooking(saved);
        return toResponse(saved);
    }

    private void confirmLinkedAdvanceBooking(Payment payment) {
        Booking booking = payment.getBooking();
        if (booking == null || booking.getStatus() != BookingStatus.PENDING || booking.getGrandTotal() == null) {
            return;
        }

        BigDecimal expectedAdvance = BigDecimal.valueOf(booking.getGrandTotal())
                .multiply(BigDecimal.valueOf(0.20))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal paidAmount = payment.getAmount() != null
                ? payment.getAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        if (paidAmount.compareTo(expectedAdvance) == 0) {
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
        }
    }
    private Order createRazorpayOrder(int amountInPaise, String currency, String receipt, Booking booking) {
        try {
            RazorpayClient razorpayClient = razorpayClient();
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", currency);
            orderRequest.put("receipt", receipt);
            orderRequest.put("payment_capture", 1);

            JSONObject notes = new JSONObject();
            notes.put("bookingId", booking == null ? "" : String.valueOf(booking.getId()));
            notes.put("source", "EvGenee Spring Boot");
            orderRequest.put("notes", notes);

            return razorpayClient.orders.create(orderRequest);
        } catch (Exception e) {
            throw new BadRequestException("Unable to create Razorpay order: " + e.getMessage());
        }
    }

    private boolean verifyPayment(PaymentRequestDto requestDto, Payment payment, String paymentId) {
        String signature = signatureFrom(requestDto);
        if (signature != null && !signature.isBlank()) {
            return verifyPaymentSignature(requestDto.getOrderId(), paymentId, signature);
        }
        return verifyPaymentByFetchingFromRazorpay(payment, paymentId);
    }

    private boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", orderId);
            attributes.put("razorpay_payment_id", paymentId);
            attributes.put("razorpay_signature", signature);
            return Utils.verifyPaymentSignature(attributes, razorpayKeySecret);
        } catch (Exception e) {
            throw new BadRequestException("Unable to verify Razorpay signature: " + e.getMessage());
        }
    }

    private boolean verifyPaymentByFetchingFromRazorpay(Payment payment, String paymentId) {
        try {
            RazorpayClient razorpayClient = razorpayClient();
            com.razorpay.Payment razorpayPayment = razorpayClient.payments.fetch(paymentId);
            String razorpayOrderId = razorpayPayment.get("order_id");
            String razorpayStatus = razorpayPayment.get("status");
            Integer razorpayAmount = razorpayPayment.get("amount");
            String method = razorpayPayment.get("method");

            payment.setRazorpayStatus(razorpayStatus);
            payment.setMethod(parseMethod(method));

            int expectedAmount = toPaise(payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO);
            boolean orderMatches = payment.getOrderId().equals(razorpayOrderId);
            boolean amountMatches = expectedAmount <= 0 || razorpayAmount != null && razorpayAmount == expectedAmount;
            boolean statusOk = "captured".equalsIgnoreCase(razorpayStatus) || "authorized".equalsIgnoreCase(razorpayStatus);
            return orderMatches && amountMatches && statusOk;
        } catch (Exception e) {
            throw new BadRequestException("Unable to verify Razorpay payment: " + e.getMessage());
        }
    }

    private PaymentResponseDto toOrderResponse(Payment payment, int amountInPaise) {
        return PaymentResponseDto.builder()
                .keyId(razorpayKeyId)
                .id(payment.getOrderId())
                .orderId(payment.getOrderId())
                .receipt(payment.getReceipt())
                .bookingId(payment.getBooking() == null ? null : payment.getBooking().getId())
                .amount(BigDecimal.valueOf(amountInPaise))
                .currency(payment.getCurrency() != null ? payment.getCurrency() : "INR")
                .method(payment.getMethod())
                .status(payment.getStatus())
                .transactionId(payment.getTransactionId())
                .paidAt(payment.getPaidAt())
                .build();
    }

    private PaymentResponseDto toResponse(Payment payment) {
        return PaymentResponseDto.builder()
                .keyId(razorpayKeyId)
                .id(String.valueOf(payment.getId()))
                .orderId(payment.getOrderId())
                .receipt(payment.getReceipt())
                .bookingId(payment.getBooking() == null ? null : payment.getBooking().getId())
                .amount(payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO)
                .currency(payment.getCurrency() != null ? payment.getCurrency() : "INR")
                .method(payment.getMethod())
                .status(payment.getStatus())
                .transactionId(payment.getTransactionId())
                .paidAt(payment.getPaidAt())
                .build();
    }

    private int toPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private RazorpayClient razorpayClient() {
        RazorpayClient client = razorpayClientProvider.getIfAvailable();
        if (client == null || razorpayKeySecret == null || razorpayKeySecret.isBlank()) {
            throw new BadRequestException("Razorpay credentials are not configured");
        }
        return client;
    }

    private BigDecimal fromPaise(int amountInPaise) {
        return BigDecimal.valueOf(amountInPaise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private String buildReceipt(Booking booking) {
        String suffix = booking == null ? UUID.randomUUID().toString().substring(0, 8) : String.valueOf(booking.getId());
        String receipt = "evgenee_" + suffix + "_" + System.currentTimeMillis();
        return receipt.length() > 40 ? receipt.substring(0, 40) : receipt;
    }

    private String signatureFrom(PaymentRequestDto requestDto) {
        if (requestDto.getRazorpaySignature() != null) return requestDto.getRazorpaySignature();
        if (requestDto.getSignature() != null) return requestDto.getSignature();
        return requestDto.getPaymentSignature();
    }

    private PaymentMethod parseMethod(String method) {
        if (method == null || method.isBlank()) return PaymentMethod.OTHERS;
        String normalized = method.trim().replace("-", "_").toUpperCase(Locale.ROOT);
        if ("NETBANKING".equals(normalized)) return PaymentMethod.OTHERS;
        try {
            return PaymentMethod.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return PaymentMethod.OTHERS;
        }
    }
}
