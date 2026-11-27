package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.BarcodeLookupResult
import com.example.myapplication.data.model.Product
import com.example.myapplication.data.model.Store
import com.example.myapplication.ui.components.BarcodeScannerDialog
import com.example.myapplication.ui.components.ConfirmActionDialog
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.NumpadAmountDialog
import com.example.myapplication.ui.components.ProductQrPrintDialog
import com.example.myapplication.ui.components.QrCodeImage
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.StatusPill
import com.example.myapplication.ui.components.copyToClipboard
import com.example.myapplication.ui.components.formatYen

@Composable
fun ProductsTab(
    store: Store?,
    products: List<Product>,
    isAdmin: Boolean,
    buildProductQrUrl: (Store, Product) -> String,
    buildEslImageUrl: (Store, Product) -> String,
    onCreateProduct: (name: String, price: Int, taxRate: Int, barcode: String?, ageRestricted: Boolean, onSuccess: () -> Unit) -> Unit,
    onUpdateProduct: (id: String, name: String, price: Int, taxRate: Int, barcode: String?, ageRestricted: Boolean, onSuccess: () -> Unit) -> Unit,
    onDeleteProduct: (String) -> Unit,
    onSetAvailability: (String, Boolean) -> Unit,
    onLookupBarcode: (String, (BarcodeLookupResult) -> Unit) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var barcode by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableIntStateOf(0) }
    var taxRate by remember { mutableIntStateOf(8) }
    var ageRestricted by remember { mutableStateOf(false) }
    var lookupNotice by remember { mutableStateOf<String?>(null) }
    var duplicateProduct by remember { mutableStateOf<Product?>(null) }

    var showScanner by remember { mutableStateOf(false) }
    var showPriceNumpad by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Product?>(null) }
    var printQrTarget by remember { mutableStateOf<Product?>(null) }

    val context = LocalContext.current

    fun resetForm() {
        editingId = null
        barcode = ""
        name = ""
        price = 0
        taxRate = 8
        ageRestricted = false
        lookupNotice = null
        duplicateProduct = null
    }

    fun startEdit(product: Product) {
        editingId = product.id
        barcode = product.barcode.orEmpty()
        name = product.name
        price = product.price
        taxRate = product.taxRate
        ageRestricted = product.ageRestricted
        lookupNotice = null
        duplicateProduct = null
    }

    fun handleBarcodeScanned(code: String) {
        // qr-order 仕様: バーコード読取時はそれまでの入力をクリアして照会
        editingId = null
        barcode = code
        name = ""
        price = 0
        ageRestricted = false
        lookupNotice = "バーコード $code を照会しています…"
        duplicateProduct = null

        onLookupBarcode(code) { result ->
            if (result.existingProduct != null) {
                duplicateProduct = result.existingProduct
                lookupNotice = "このバーコードは「${result.existingProduct.name}」として登録済みです。"
            } else if (result.suggestedName != null) {
                name = result.suggestedName
                lookupNotice = "${result.source} から商品名を自動入力しました。内容を確認し、価格を設定してください。"
            } else {
                lookupNotice = "商品情報が見つかりませんでした。商品名と価格を手で入力してください。"
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isAdmin) {
            item(key = "product_form") {
                SectionHeader(
                    title = if (editingId == null) "商品を登録する" else "商品を編集する",
                    subtitle = "バーコード（JAN）は任意です。価格は 50 円以上の税込金額を設定してください。"
                )
                Spacer(Modifier.height(6.dp))
                StaffCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = barcode,
                            onValueChange = { barcode = it },
                            label = { Text("バーコード（JAN・任意）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { showScanner = true }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("カメラ")
                        }
                    }

                    if (barcode.isNotBlank() && editingId == null) {
                        TextButton(onClick = { handleBarcodeScanned(barcode.trim()) }) {
                            Text("このバーコードで商品名を検索する")
                        }
                    }

                    lookupNotice?.let { msg ->
                        NoticeBanner(text = msg, isWarning = duplicateProduct != null)
                    }

                    duplicateProduct?.let { existing ->
                        OutlinedButton(
                            onClick = { startEdit(existing) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("この商品を編集する（${existing.name}）")
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("商品名（必須）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "価格（税込・50円以上）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (price > 0) formatYen(price) else "未入力",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        OutlinedButton(onClick = { showPriceNumpad = true }) {
                            Icon(Icons.Default.Dialpad, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("テンキーで入力")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "税率:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        FilterChip(
                            selected = taxRate == 8,
                            onClick = { taxRate = 8 },
                            label = { Text("8%（軽減税率・飲食料品）") }
                        )
                        FilterChip(
                            selected = taxRate == 10,
                            onClick = { taxRate = 10 },
                            label = { Text("10%（標準）") }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "年齢確認が必要（20歳以上）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "酒類・たばこ等の場合オンにします",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ageRestricted,
                            onCheckedChange = { ageRestricted = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val currentEditId = editingId
                                if (currentEditId == null) {
                                    onCreateProduct(
                                        name,
                                        price,
                                        taxRate,
                                        barcode.ifBlank { null },
                                        ageRestricted
                                    ) { resetForm() }
                                } else {
                                    onUpdateProduct(
                                        currentEditId,
                                        name,
                                        price,
                                        taxRate,
                                        barcode.ifBlank { null },
                                        ageRestricted
                                    ) { resetForm() }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (editingId == null) "登録する" else "更新する",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (editingId != null || name.isNotBlank() || barcode.isNotBlank() || price > 0) {
                            OutlinedButton(onClick = { resetForm() }) {
                                Text("クリア")
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "staff_notice") {
                NoticeBanner(text = "スタッフ権限では「有効／無効」の切り替え（品切れ対応）のみ操作できます。商品の登録・編集・削除は管理者権限で行います。")
            }
        }

        item(key = "products_header") {
            SectionHeader(
                title = "商品一覧",
                badgeCount = products.size,
                subtitle = "QR コードを押すと印刷用に大きく開きます。「無効」にした商品はお客様が読み取ってもカートに入りません。"
            )
        }

        items(products, key = { it.id }) { product ->
            val qrUrl = store?.let { buildProductQrUrl(it, product) }.orEmpty()
            ProductItemCard(
                product = product,
                qrUrl = qrUrl,
                isAdmin = isAdmin,
                onQrClick = { printQrTarget = product },
                onToggleAvailable = { onSetAvailability(product.id, !product.available) },
                onEdit = { startEdit(product) },
                onDelete = { deleteTarget = product }
            )
        }

        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }

    if (showScanner) {
        BarcodeScannerDialog(
            onScanned = { code -> handleBarcodeScanned(code) },
            onDismiss = { showScanner = false }
        )
    }

    if (showPriceNumpad) {
        NumpadAmountDialog(
            title = "価格を入力（税込）",
            subtitle = "50 円以上で設定してください。",
            initialAmount = price,
            minAmount = 50,
            confirmLabel = "価格を決定",
            onConfirm = { price = it },
            onDismiss = { showPriceNumpad = false }
        )
    }

    deleteTarget?.let { product ->
        ConfirmActionDialog(
            title = "商品を削除しますか？",
            message = "「${product.name}」を削除します。元に戻せません（過去の注文履歴には影響しません）。",
            confirmLabel = "削除する",
            isDanger = true,
            onConfirm = {
                if (editingId == product.id) resetForm()
                onDeleteProduct(product.id)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    printQrTarget?.let { product ->
        if (store != null) {
            val qrUrl = buildProductQrUrl(store, product)
            val eslUrl = buildEslImageUrl(store, product)
            ProductQrPrintDialog(
                productName = product.name,
                price = product.price,
                taxRate = product.taxRate,
                qrUrl = qrUrl,
                eslUrl = eslUrl,
                onCopyEslUrl = {
                    copyToClipboard(context, "電子棚札 URL", eslUrl)
                    onShowMessage("電子棚札の画像 URL をコピーしました")
                },
                onDismiss = { printQrTarget = null }
            )
        }
    }
}

@Composable
private fun ProductItemCard(
    product: Product,
    qrUrl: String,
    isAdmin: Boolean,
    onQrClick: () -> Unit,
    onToggleAvailable: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    StaffCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            QrCodeImage(
                content = qrUrl.ifBlank { product.id },
                size = 76.dp,
                contentDescription = "${product.name} の QR コード",
                onClick = onQrClick
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = if (product.available) "販売中" else "無効（品切れ）",
                        containerColor = if (product.available) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (product.available) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (product.ageRestricted) {
                        StatusPill(
                            text = "年齢確認 20+",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${formatYen(product.price)}（税込・${product.taxRate}%）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!product.barcode.isNullOrBlank()) {
                    Text(
                        text = "JAN: ${product.barcode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onToggleAvailable,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (product.available) "無効にする" else "有効にする")
            }
            if (isAdmin) {
                OutlinedButton(onClick = onEdit) {
                    Text("編集")
                }
                OutlinedButton(onClick = onDelete) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
