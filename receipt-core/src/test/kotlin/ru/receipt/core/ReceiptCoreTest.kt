package ru.receipt.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class ReceiptCoreTest {
    private val q1 = "t=20261018T1958&s=1450.00&fn=7380440700076549&i=12345&fp=1234567890&n=1"
    private val q2 = "t=20261018T195833&s=179.90&fn=7380440700076549&i=12346&fp=2234567890&n=1"
    private val o1 = """ООО "РОМАШКА"
КАССОВЫЙ ЧЕК
ПРИХОД
Молоко 3,2% 1л
1 x 89.90 =89.90
Хлеб бородинский
2 x 45.00 =90.00
ИТОГ =179.90
НАЛИЧНЫМИ =200.00
СДАЧА =20.10
18.10.26 19:58""".lines()
    private val o2 = """ИП Иванов И.И.
Кофе капучино 0,3
1 * 250,00 = 250,00
Круассан
1 * 120,00 = 120,00
ИТОГО: 370,00
БЕЗНАЛИЧНЫМИ: 370,00
18.10.2026 09:15""".lines()
    private val o3 = """ООО "ТЕХНОМИР"
Наушники беспроводные
1 x 1 299.00 =1 299.00
Чехол
1 x 151.00 =151.00
Итог =1 450.00
18.10.26 19:58""".lines()
    private val o4 = """Молоко 1л
1 x 89.90 =89.90""".lines()
    private val o5 = """Вода 0,5л
3 x 40.00 =120.00
ПОЛУЧЕНО =500.00
СДАЧА =380.00
К ОПЛАТЕ =120.00""".lines()
    private val o6 = """Яблоки Гала кг
0.512 x 199.00 =101.89
Пакет
1 x 5.00 =5.00
ИТОГ =106.89
18.10.26 20:01""".lines()
    private val o7 = """Сыр полутвердый
Российский 45% 200г
1 x 259.00 =259.00
ИТОГ =259.00""".lines()
    private val o8 = """Молоко 3,2% 1л
1 x 89.90 =89.90
ИТОГ =179.90""".lines()
    private fun qr(value: String) = (parseQr(value) as QrResult.Success).data
    private fun item(actual: Item, name: String, quantity: String, price: Long, amount: Long) {
        assertEquals(name, actual.name)
        assertEquals(0, BigDecimal(quantity).compareTo(actual.quantity))
        assertEquals(price, actual.priceKopecks)
        assertEquals(amount, actual.amountKopecks)
    }

    @Test fun q1() { val v = qr(q1); assertEquals(LocalDateTime.of(2026, 10, 18, 19, 58), v.dateTime); assertEquals(145000, v.sumKopecks); assertEquals("7380440700076549", v.fn); assertEquals("12345", v.documentNumber); assertEquals("1234567890", v.fiscalSign); assertEquals(OperationType.INCOME, v.operationType) }
    @Test fun q2() { val v = qr(q2); assertEquals(LocalDateTime.of(2026, 10, 18, 19, 58, 33), v.dateTime); assertEquals(17990, v.sumKopecks) }
    @Test fun q3() { val v = qr("n=1&fp=3234567890&i=12347&fn=7380440700076549&s=99.9&t=20261018T2001"); assertEquals(LocalDateTime.of(2026, 10, 18, 20, 1), v.dateTime); assertEquals(9990, v.sumKopecks) }
    @Test fun q4() { assertEquals(50000, qr("t=20261018T2010&s=500&fn=7380440700076549&i=12348&fp=4234567890&n=1").sumKopecks) }
    @Test fun q5() { assertEquals(OperationType.INCOME_RETURN, qr("t=20261018T2015&s=1450.00&fn=7380440700076549&i=12349&fp=5234567890&n=2").operationType) }
    @Test fun q6() { assertEquals(QrResult.MissingField("s"), parseQr("t=20261018T1958&fn=7380440700076549&i=12345&fp=1234567890&n=1")) }
    @Test fun q7() { assertEquals(QrResult.NotAReceipt, parseQr("https://example.com")) }
    @Test fun q8() { assertEquals(QrResult.InvalidField("t"), parseQr("t=2026-10-18&s=10.00&fn=1&i=1&fp=1&n=1")) }
    @Test fun t1() { val t = parseReceiptText(o1); assertEquals(17990L, t.totalKopecks); assertEquals(LocalDateTime.of(2026, 10, 18, 19, 58), t.dateTime) }
    @Test fun t2() { val t = parseReceiptText(o2); assertEquals(37000L, t.totalKopecks); assertEquals(LocalDateTime.of(2026, 10, 18, 9, 15), t.dateTime) }
    @Test fun t3() { val t = parseReceiptText(o3); assertEquals(145000L, t.totalKopecks); assertEquals(LocalDateTime.of(2026, 10, 18, 19, 58), t.dateTime) }
    @Test fun t4() { val t = parseReceiptText(o4); assertNull(t.totalKopecks); assertNull(t.dateTime) }
    @Test fun t5() { val t = parseReceiptText(o5); assertEquals(12000L, t.totalKopecks); assertNull(t.dateTime) }
    @Test fun i1() { val v = parseReceiptText(o1).items; assertEquals(2, v.size); item(v[0], "Молоко 3,2% 1л", "1", 8990, 8990); item(v[1], "Хлеб бородинский", "2", 4500, 9000) }
    @Test fun i2() { val v = parseReceiptText(o2).items; assertEquals(2, v.size); item(v[0], "Кофе капучино 0,3", "1", 25000, 25000); item(v[1], "Круассан", "1", 12000, 12000) }
    @Test fun i3() { val v = parseReceiptText(o3).items; assertEquals(2, v.size); item(v[0], "Наушники беспроводные", "1", 129900, 129900); item(v[1], "Чехол", "1", 15100, 15100) }
    @Test fun i4() { val v = parseReceiptText(o6).items; assertEquals(2, v.size); item(v[0], "Яблоки Гала кг", "0.512", 19900, 10189); item(v[1], "Пакет", "1", 500, 500) }
    @Test fun i5() { val v = parseReceiptText(o7).items; assertEquals(1, v.size); item(v[0], "Сыр полутвердый Российский 45% 200г", "1", 25900, 25900) }
    @Test fun r1() { assertEquals(ReconcileStatus.OK, reconcile(qr(q1), parseReceiptText(o3))) }
    @Test fun r2() { assertEquals(ReconcileStatus.UNVERIFIED, reconcile(null, parseReceiptText(o1))) }
    @Test fun r3() { assertEquals(ReconcileStatus.TOTAL_MISMATCH, reconcile(qr(q1), parseReceiptText(o1))) }
    @Test fun r4() { assertEquals(ReconcileStatus.ITEMS_MISMATCH, reconcile(null, parseReceiptText(o8))) }
    @Test fun r5() { assertEquals(ReconcileStatus.NO_TOTAL, reconcile(null, parseReceiptText(o4))) }
    @Test fun r6() { assertEquals(ReconcileStatus.OK, reconcile(qr(q2), parseReceiptText(o1))) }
    @Test fun d1() { val history = ReceiptHistory(); assertTrue(history.save(qr(q1))); assertFalse(history.save(qr(q1))); assertEquals(1, history.count()) }
    @Test fun d2() { val history = ReceiptHistory(); assertTrue(history.save(qr(q1))); assertTrue(history.save(qr(q2))); assertEquals(2, history.count()) }
}
