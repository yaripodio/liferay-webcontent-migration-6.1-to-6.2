<h1>Web content Migration Util</h1>
<h3>Liferay 6.1 to Liferay 6.2</h3>

<p>
	When migrating contents from Liferay 6.1 version to 6.2 version, there is a common problem with the migrated structures. In Liferay 6.2, the structure's
	fields names must be unique globally, while in 6.1 they had to be unique only in their hierarchy level. The automatic database migration feature of Liferay
	doesn't have this into account, so the structures with repeated field names are migrated without changes, causing several problems (i.e the structures
	cannot be edited)
</p>
<p>
    There's another problem with the structure fields of type "date". In Liferay 6.1, the type identifier was the string "date", while in Liferay 6.2 the
    identifier has been changed to "ddm-date". This makes the edit of structures with fields of type "date" impossible, as it causes a Javascript error
    in the internal libraries of Liferay. Moreover, when editing a web content with that structure, the date field appears as a normal text field
</p>
<p>
	This portlet allows to check and change the web content structures with repeated field names and date type fields and all its related contents, assuring
	that no data is lost in the contents when changing the structure. To do so, the following steps are executed: 
</p>
<ul>
	<li>All the structures in the database are checked to find repeated field names or date type fields, the matching structures are stored to be changed</li>
	<li>All the web contents (but only their current active versions) that use such structures are retrieved to be changed</li>
	<li>The contents fields are changed so each different field have a different name. To do so, the first field is skipped (so it holds the
		original name), the second field is renamed as the original name plus "_2", the third one is renamed as the original plus "_3", et
		cetera. The process is done in a way that is compatible with all the ocurrences of the repeatable fields</li>
	<li>When all the contents have been changed, the structure is changed also, renaming the fields with the same logic that has been used
		for the contents. Also, all structure fields of type "date" are updated to type "ddm-date"</li>
	<li>Finally, all the templates related with contents and structures that have been changed are listed so the portal admin can update them with the new
		field names</li>
</ul>