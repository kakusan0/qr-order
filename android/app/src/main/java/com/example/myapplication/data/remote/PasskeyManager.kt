package com.example.myapplication.data.remote

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException

/**
 * Android Credential Manager API を使ってネイティブにパスキー認証・登録を行うヘルパー。
 *
 * 注意: ネイティブ Credential Manager からのパスキー認証をサーバーが受理するには、
 * サーバー側 (/.well-known/assetlinks.json) に本アプリのパッケージ名と SHA-256 を登録し、
 * verifyAuthenticationResponse / verifyRegistrationResponse の expectedOrigin に
 * "android:apk-key-hash:..." を許可する必要があります。
 * 未設定の場合はアプリ内「ブラウザ認証（WebView）」からログインしてください。
 */
object PasskeyManager {

    suspend fun authenticate(activity: Activity, optionsJson: String): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Result.failure(
                IllegalStateException("この端末（Android 8 以下）ではネイティブパスキーに未対応です。「ブラウザ認証でログイン」を使用してください。")
            )
        }
        return try {
            val credentialManager = CredentialManager.create(activity)
            val request = GetCredentialRequest(
                listOf(GetPublicKeyCredentialOption(requestJson = optionsJson))
            )
            val response = credentialManager.getCredential(activity, request)
            val publicKey = response.credential as? PublicKeyCredential
                ?: return Result.failure(IllegalStateException("パスキー認証結果を取得できませんでした"))
            Result.success(publicKey.authenticationResponseJson)
        } catch (_: GetCredentialCancellationException) {
            Result.failure(IllegalStateException("パスキー認証がキャンセルされました"))
        } catch (_: NoCredentialException) {
            Result.failure(
                IllegalStateException(
                    "このアプリに紐づくパスキーが見つかりませんでした。「ブラウザ認証でログイン」をお試しください。"
                )
            )
        } catch (e: GetCredentialException) {
            Result.failure(
                IllegalStateException(
                    "ネイティブパスキー認証エラー（${e.message ?: "未設定"}）。サーバーの assetlinks.json / expectedOrigin 未対応の場合は「ブラウザ認証でログイン」を使用してください。"
                )
            )
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(e.message ?: "パスキー認証に失敗しました"))
        }
    }

    suspend fun register(activity: Activity, optionsJson: String): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Result.failure(
                IllegalStateException("この端末（Android 8 以下）ではネイティブパスキー登録に未対応です。「ブラウザ認証」を使用してください。")
            )
        }
        return try {
            val credentialManager = CredentialManager.create(activity)
            val request = CreatePublicKeyCredentialRequest(requestJson = optionsJson)
            val response = credentialManager.createCredential(activity, request)
            val created = response as? CreatePublicKeyCredentialResponse
                ?: return Result.failure(IllegalStateException("パスキー登録結果を取得できませんでした"))
            Result.success(created.registrationResponseJson)
        } catch (_: CreateCredentialCancellationException) {
            Result.failure(IllegalStateException("パスキー登録がキャンセルされました"))
        } catch (e: CreateCredentialException) {
            Result.failure(
                IllegalStateException(
                    "ネイティブパスキー登録エラー（${e.message ?: "未設定"}）。サーバー側が Android Origin 未対応の場合は「ブラウザ認証」から登録してください。"
                )
            )
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(e.message ?: "パスキー登録に失敗しました"))
        }
    }
}
