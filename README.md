{
								"pageSize" : 10,
								"pageNum" : 1,
								"criteriaList" : [{
									"paramaList" : [{
										"name" : ev.opts.key,
										"type" : "DATE",
										"minValue" : moment(ev.model.startDate)
												.format('DD-MM-YYYY'),
										"maxValue" : moment(ev.model.endDate)
												.format('DD-MM-YYYY'),
										"reqFormat" : "dd-MM-yyyy"
									}]
								}]
							}
