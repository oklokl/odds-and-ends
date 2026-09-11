package com.krdonon.microphone.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.krdonon.microphone.data.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*

private val Context.categoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "categories")

class CategoryRepository(private val context: Context) {
    
    private val gson = Gson()
    private val CATEGORIES_KEY = stringPreferencesKey("categories_list")
    
    val categoriesFlow: Flow<List<Category>> = context.categoryDataStore.data.map { preferences ->
        val json = preferences[CATEGORIES_KEY] ?: "[]"
        val type = object : TypeToken<List<Category>>() {}.type
        gson.fromJson<List<Category>>(json, type) ?: emptyList()
    }
    
    suspend fun addCategory(name: String): Boolean {
        if (name.isBlank()) return false
        
        context.categoryDataStore.edit { preferences ->
            val currentJson = preferences[CATEGORIES_KEY] ?: "[]"
            val type = object : TypeToken<List<Category>>() {}.type
            val currentList = gson.fromJson<List<Category>>(currentJson, type) ?: emptyList()
            
            // 중복 체크
            if (currentList.any { it.name == name }) {
                return@edit
            }
            
            val newCategory = Category(
                id = UUID.randomUUID().toString(),
                name = name
            )
            val updatedList = currentList + newCategory
            preferences[CATEGORIES_KEY] = gson.toJson(updatedList)
        }
        return true
    }
    
    suspend fun deleteCategory(categoryId: String) {
        context.categoryDataStore.edit { preferences ->
            val currentJson = preferences[CATEGORIES_KEY] ?: "[]"
            val type = object : TypeToken<List<Category>>() {}.type
            val currentList = gson.fromJson<List<Category>>(currentJson, type) ?: emptyList()
            
            val updatedList = currentList.filter { it.id != categoryId }
            preferences[CATEGORIES_KEY] = gson.toJson(updatedList)
        }
    }
    
    suspend fun renameCategory(categoryId: String, newName: String): Boolean {
        if (newName.isBlank()) return false
        
        context.categoryDataStore.edit { preferences ->
            val currentJson = preferences[CATEGORIES_KEY] ?: "[]"
            val type = object : TypeToken<List<Category>>() {}.type
            val currentList = gson.fromJson<List<Category>>(currentJson, type) ?: emptyList()
            
            // 중복 체크
            if (currentList.any { it.name == newName && it.id != categoryId }) {
                return@edit
            }
            
            val updatedList = currentList.map { category ->
                if (category.id == categoryId) {
                    category.copy(name = newName)
                } else {
                    category
                }
            }
            preferences[CATEGORIES_KEY] = gson.toJson(updatedList)
        }
        return true
    }
}
