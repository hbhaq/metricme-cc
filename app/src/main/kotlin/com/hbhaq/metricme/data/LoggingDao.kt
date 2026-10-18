package com.hbhaq.metricme.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class FoodWithComponents(@Embedded val entry: FoodEntity, @Relation(parentColumn = "id", entityColumn = "foodId") val components: List<FoodComponentEntity>)
data class ActivityWithExercises(@Embedded val entry: ActivityEntity, @Relation(parentColumn = "id", entityColumn = "activityId") val exercises: List<ExerciseEntity>)
@Dao interface LoggingDao {
    @Transaction @Query("SELECT * FROM FoodEntry WHERE profileId='local' AND confirmation='CONFIRMED' ORDER BY instant DESC") fun foods(): Flow<List<FoodWithComponents>>
    @Transaction @Query("SELECT * FROM ActivityEntry WHERE profileId='local' AND confirmation='CONFIRMED' ORDER BY instant DESC") fun activities(): Flow<List<ActivityWithExercises>>
    @Query("SELECT * FROM StepEntry WHERE profileId='local' AND confirmation='CONFIRMED' ORDER BY instant DESC, createdAt DESC, id DESC") fun steps(): Flow<List<StepEntity>>
    @Query("SELECT * FROM SleepEntry WHERE profileId='local' AND confirmation='CONFIRMED' ORDER BY wakeTime DESC") fun sleeps(): Flow<List<SleepEntity>>
    @Query("SELECT * FROM SleepEntry WHERE profileId='local' AND id != :except AND bedtime < :end AND wakeTime > :start") suspend fun overlaps(except: String, start: Long, end: Long): List<SleepEntity>
    @Query("SELECT * FROM FoodEntry WHERE id=:id") suspend fun food(id: String): FoodEntity?
    @Query("SELECT * FROM ActivityEntry WHERE id=:id") suspend fun activity(id: String): ActivityEntity?
    @Query("SELECT * FROM StepEntry WHERE id=:id") suspend fun step(id: String): StepEntity?
    @Query("SELECT * FROM SleepEntry WHERE id=:id") suspend fun sleep(id: String): SleepEntity?
    @Upsert suspend fun food(value: FoodEntity)
    @Insert suspend fun components(values: List<FoodComponentEntity>)
    @Upsert suspend fun activity(value: ActivityEntity)
    @Insert suspend fun exercises(values: List<ExerciseEntity>)
    @Upsert suspend fun step(value: StepEntity)
    @Upsert suspend fun sleep(value: SleepEntity)
    @Query("DELETE FROM FoodComponent WHERE foodId=:id") suspend fun deleteComponents(id: String)
    @Query("DELETE FROM ExerciseEntry WHERE activityId=:id") suspend fun deleteExercises(id: String)
    @Query("DELETE FROM FoodEntry WHERE id=:id AND profileId='local'") suspend fun deleteFood(id: String)
    @Query("DELETE FROM ActivityEntry WHERE id=:id AND profileId='local'") suspend fun deleteActivity(id: String)
    @Query("DELETE FROM StepEntry WHERE id=:id AND profileId='local'") suspend fun deleteStep(id: String)
    @Query("DELETE FROM SleepEntry WHERE id=:id AND profileId='local'") suspend fun deleteSleep(id: String)
}
