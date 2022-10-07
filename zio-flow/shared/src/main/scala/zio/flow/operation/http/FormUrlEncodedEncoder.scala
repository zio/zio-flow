package zio.flow.operation.http

import zio.Chunk
import zio.schema.{Schema, StandardType}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZonedDateTime}
import scala.collection.immutable.ListMap
import scala.collection.mutable

object FormUrlEncodedEncoder {
  def encode[A](schema: Schema[A]): A => Chunk[Byte] = { (value: A) =>
    val builder = new mutable.StringBuilder
    encode(builder, "", schema)(value)
    Chunk.fromArray(builder.toString().getBytes(StandardCharsets.UTF_8))
  }

  def encode[A](builder: mutable.StringBuilder, label: String, schema: Schema[A]): A => Unit = {
    def addField[A](fieldName: String, schema: Schema[A], fieldValue: Any): Unit = {
      encode(builder, fieldName, schema.asInstanceOf[Schema[Any]])(fieldValue)
      ()
    }

    (value: A) =>
      schema match {
        case record: Schema.GenericRecord =>
          val data = value.asInstanceOf[ListMap[String, _]]
          record.fieldSet.toChunk.foreach { field =>
            addField(field.label, field.schema, data(field.label))
          }
        case cls: Schema.CaseClass1[_, _] =>
          addField(cls.field.label, cls.field.schema, cls.extractField(value))
        case cls: Schema.CaseClass2[_, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
        case cls: Schema.CaseClass3[_, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
        case cls: Schema.CaseClass4[_, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
        case cls: Schema.CaseClass5[_, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
        case cls: Schema.CaseClass6[_, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
        case cls: Schema.CaseClass7[_, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
        case cls: Schema.CaseClass8[_, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
        case cls: Schema.CaseClass9[_, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
        case cls: Schema.CaseClass10[_, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
        case cls: Schema.CaseClass11[_, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
        case cls: Schema.CaseClass12[_, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
        case cls: Schema.CaseClass13[_, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
        case cls: Schema.CaseClass14[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
        case cls: Schema.CaseClass15[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
        case cls: Schema.CaseClass16[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
        case cls: Schema.CaseClass17[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
          addField(cls.field17.label, cls.field17.schema, cls.extractField17(value))
        case cls: Schema.CaseClass18[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
          addField(cls.field17.label, cls.field17.schema, cls.extractField17(value))
          addField(cls.field18.label, cls.field18.schema, cls.extractField18(value))
        case cls: Schema.CaseClass19[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
          addField(cls.field17.label, cls.field17.schema, cls.extractField17(value))
          addField(cls.field18.label, cls.field18.schema, cls.extractField18(value))
          addField(cls.field19.label, cls.field19.schema, cls.extractField19(value))
        case cls: Schema.CaseClass20[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
          addField(cls.field17.label, cls.field17.schema, cls.extractField17(value))
          addField(cls.field18.label, cls.field18.schema, cls.extractField18(value))
          addField(cls.field19.label, cls.field19.schema, cls.extractField19(value))
          addField(cls.field20.label, cls.field20.schema, cls.extractField20(value))
        case cls: Schema.CaseClass21[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
          addField(cls.field17.label, cls.field17.schema, cls.extractField17(value))
          addField(cls.field18.label, cls.field18.schema, cls.extractField18(value))
          addField(cls.field19.label, cls.field19.schema, cls.extractField19(value))
          addField(cls.field20.label, cls.field20.schema, cls.extractField20(value))
          addField(cls.field21.label, cls.field21.schema, cls.extractField21(value))
        case cls: Schema.CaseClass22[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _] =>
          addField(cls.field1.label, cls.field1.schema, cls.extractField1(value))
          addField(cls.field2.label, cls.field2.schema, cls.extractField2(value))
          addField(cls.field3.label, cls.field3.schema, cls.extractField3(value))
          addField(cls.field4.label, cls.field4.schema, cls.extractField4(value))
          addField(cls.field5.label, cls.field5.schema, cls.extractField5(value))
          addField(cls.field6.label, cls.field6.schema, cls.extractField6(value))
          addField(cls.field7.label, cls.field7.schema, cls.extractField7(value))
          addField(cls.field8.label, cls.field8.schema, cls.extractField8(value))
          addField(cls.field9.label, cls.field9.schema, cls.extractField9(value))
          addField(cls.field10.label, cls.field10.schema, cls.extractField10(value))
          addField(cls.field11.label, cls.field11.schema, cls.extractField11(value))
          addField(cls.field12.label, cls.field12.schema, cls.extractField12(value))
          addField(cls.field13.label, cls.field13.schema, cls.extractField13(value))
          addField(cls.field14.label, cls.field14.schema, cls.extractField14(value))
          addField(cls.field15.label, cls.field15.schema, cls.extractField15(value))
          addField(cls.field16.label, cls.field16.schema, cls.extractField16(value))
          addField(cls.field17.label, cls.field17.schema, cls.extractField17(value))
          addField(cls.field18.label, cls.field18.schema, cls.extractField18(value))
          addField(cls.field19.label, cls.field19.schema, cls.extractField19(value))
          addField(cls.field20.label, cls.field20.schema, cls.extractField20(value))
          addField(cls.field21.label, cls.field21.schema, cls.extractField21(value))
          addField(cls.field22.label, cls.field22.schema, cls.extractField22(value))
        case transform: Schema.Transform[_, _, _] =>
          encode(builder, label, transform.codec)(
            transform.g(value).fold((failure: String) => throw new RuntimeException(failure), value => value)
          )
        case primitive: Schema.Primitive[_] =>
          if (builder.nonEmpty) builder.append('&')
          if (label.nonEmpty) builder.append(s"$label=")
          primitive.standardType match {
            case StandardType.InstantType(formatter) =>
              builder.append(URLEncoder.encode(formatter.format(value.asInstanceOf[Instant]), StandardCharsets.UTF_8));
              ()
            case StandardType.LocalDateType(formatter) =>
              builder.append(
                URLEncoder.encode(formatter.format(value.asInstanceOf[LocalDate]), StandardCharsets.UTF_8)
              );
              ()
            case StandardType.LocalTimeType(formatter) =>
              builder.append(
                URLEncoder.encode(formatter.format(value.asInstanceOf[LocalTime]), StandardCharsets.UTF_8)
              );
              ()
            case StandardType.LocalDateTimeType(formatter) =>
              builder.append(
                URLEncoder.encode(formatter.format(value.asInstanceOf[LocalDateTime]), StandardCharsets.UTF_8)
              );
              ()
            case StandardType.OffsetTimeType(formatter) =>
              builder.append(
                URLEncoder.encode(formatter.format(value.asInstanceOf[OffsetTime]), StandardCharsets.UTF_8)
              );
              ()
            case StandardType.OffsetDateTimeType(formatter) =>
              builder.append(
                URLEncoder.encode(formatter.format(value.asInstanceOf[OffsetDateTime]), StandardCharsets.UTF_8)
              );
              ()
            case StandardType.ZonedDateTimeType(formatter) =>
              builder.append(
                URLEncoder.encode(formatter.format(value.asInstanceOf[ZonedDateTime]), StandardCharsets.UTF_8)
              );
              ()
            case _ => builder.append(URLEncoder.encode(value.toString, StandardCharsets.UTF_8)); ()
          }
        case optional: Schema.Optional[_] =>
          value.asInstanceOf[Option[Any]] match {
            case Some(inner) => encode(builder, label, optional.codec.asInstanceOf[Schema[Any]])(inner)
            case None        =>
          }
        case Schema.Lazy(inner) =>
          encode(builder, label, inner())(value)
        case _ =>
          throw new IllegalArgumentException(s"Schema not supported for x-www-form-urlencoded payloads")
      }
  }
}
